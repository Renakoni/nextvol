"""Extract the unchanged Genie V2ProPlus HiFiGAN graph for mixed CPU/GPU execution.

No weights, arithmetic, sampling or output cropping are changed. External weights
remain in the original directory.
"""

import argparse
import hashlib
import json
from pathlib import Path

import onnx
from onnx.utils import Extractor


BOUNDARY = "/vq_model/Mul_2_output_0"


def split(source: Path) -> tuple[Path, Path]:
    graph = onnx.load(source, load_external_data=False)
    conv = next(n for n in graph.graph.node if n.name == "/vq_model/dec/conv_pre/Conv")
    if conv.input[0] != BOUNDARY:
        raise ValueError("Unexpected Genie acoustic graph boundary")
    known = {v.name for v in graph.graph.value_info}
    if BOUNDARY not in known:
        graph.graph.value_info.append(onnx.helper.make_tensor_value_info(BOUNDARY, onnx.TensorProto.FLOAT, [1, 192, "latent_frames"]))
    extractor = Extractor(graph)
    # ONNX Extractor does not follow lexical captures inside If branches.
    # Keep the pre-resize feature/mask ancestors, then expose only the latent.
    captures = []
    if any(i.name == 'speed' for i in graph.graph.input):
        captures = ['/vq_model/enc_p/encoder2/Mul_1_output_0', '/vq_model/enc_p/ConstantOfShape_output_0']
        for name in captures:
            if name not in known:
                graph.graph.value_info.append(onnx.helper.make_tensor_value_info(name, onnx.TensorProto.FLOAT, [1, None, None]))
        extractor = Extractor(graph)
    encoder = extractor.extract_model([i.name for i in graph.graph.input], [BOUNDARY] + captures)
    del encoder.graph.output[1:]
    hifigan = extractor.extract_model([BOUNDARY, "ge"], [o.name for o in graph.graph.output])
    encoder_path = source.parent / "split-encoder.onnx"
    hifigan_path = source.parent / "split-hifigan.onnx"
    onnx.save(encoder, encoder_path)
    onnx.save(hifigan, hifigan_path)
    return encoder_path, hifigan_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    args = parser.parse_args()
    paths = split(args.model_dir / "vits_fp32.onnx")
    manifest = {"schema_version": 1, "format": "genie_v2proplus_split_v1", "boundary": BOUNDARY,
                "source_sha256": hashlib.sha256((args.model_dir / "vits_fp32.onnx").read_bytes()).hexdigest(),
                "graphs": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}}
    (args.model_dir / "split-vits.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
