"""V2ProPlus: FP32 edges and a bounded HTP convolution body, sharing source weights."""
import argparse
import hashlib
import json
from pathlib import Path

import onnx
from onnx import TensorProto, helper
from onnx.utils import Extractor

FRAMES = 64
GRAPHS = ("v2-htp-stem.onnx", "v2-htp-body.onnx", "v2-htp-head.onnx")


def split(directory: Path):
    manifest_path = directory / "manifest.json"
    model = onnx.load(directory / "vits_fp32.onnx", load_external_data=False)
    weights = {w.name: w for w in model.graph.initializer}
    stem_channels = weights["vq_model.dec.conv_pre.weight"].dims[0]
    body_channels = weights["vq_model.dec.conv_post.weight"].dims[1]
    if (stem_channels, body_channels) not in ((512, 16), (768, 24)):
        raise ValueError("Unsupported V2 vocoder channel layout")
    aliases = {"/vq_model/Mul_2_output_0": "latent", "/vq_model/dec/Add_output_0": "stem",
               "/vq_model/dec/Div_9_output_0": "body"}
    for node in model.graph.node:
        for names in (node.input, node.output):
            for index, name in enumerate(names):
                if name in aliases:
                    names[index] = aliases[name]
    del model.graph.value_info[:]
    for name, channels in (("latent", 192), ("stem", stem_channels), ("body", body_channels)):
        model.graph.value_info.append(helper.make_tensor_value_info(name, TensorProto.FLOAT, [1, channels, name + "_frames"]))
    extractor = Extractor(model)
    stem = extractor.extract_model(["latent", "ge"], ["stem"])
    body = extractor.extract_model(["stem"], ["body"])
    head = extractor.extract_model(["body"], [model.graph.output[0].name])

    # A height-one 2D graph avoids repeated 1D adapters. Weight normalization
    # remains FP32 and is folded by ORT; only its final weights acquire a view.
    axes = "htp_weight_axes"
    body.graph.initializer.append(helper.make_tensor(axes, TensorProto.INT64, [1], [2]))
    nodes = []
    for node in body.graph.node:
        if node.op_type in ("Conv", "ConvTranspose"):
            weight = node.input[1] + "/htp2d"
            nodes.append(helper.make_node("Unsqueeze", [node.input[1], axes], [weight], name=weight))
            node.input[1] = weight
            attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
            for key in ("kernel_shape", "dilations", "strides"):
                if key in attrs:
                    attrs[key] = [1] + attrs[key]
            if "pads" in attrs:
                left, right = attrs["pads"]
                attrs["pads"] = [0, left, 0, right]
            if "output_padding" in attrs:
                attrs["output_padding"] = [0] + attrs["output_padding"]
            del node.attribute[:]
            node.attribute.extend(helper.make_attribute(k, v) for k, v in attrs.items())
        nodes.append(node)
    del body.graph.node[:]
    body.graph.node.extend(nodes)
    body.graph.input[0].CopyFrom(helper.make_tensor_value_info("stem", TensorProto.FLOAT, [1, stem_channels, 1, FRAMES]))
    body.graph.output[0].CopyFrom(helper.make_tensor_value_info("body", TensorProto.FLOAT, [1, body_channels, 1, FRAMES * 640]))
    del body.graph.value_info[:]
    for name, graph in zip(GRAPHS, (stem, body, head)):
        onnx.save(onnx.shape_inference.infer_shapes(graph), directory / name)
    split_manifest = directory / "split-vits.json"
    digests = {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in GRAPHS}
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"].update(digests)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    elif split_manifest.is_file():
        manifest = json.loads(split_manifest.read_text(encoding="utf-8"))
        manifest["graphs"].update(digests)
        split_manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    split(parser.parse_args().directory)
