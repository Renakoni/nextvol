"""Extract the unchanged Genie V2ProPlus HiFiGAN graph for mixed CPU/GPU execution.

No weights, arithmetic, sampling or output cropping are changed. External weights
remain in the original directory. A fixed-noise diagnostic fixture is optional.
"""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
from onnx.utils import Extractor
import onnxruntime as ort


BOUNDARY = "/vq_model/Mul_2_output_0"


def split(source: Path, prefix: str) -> tuple[Path, Path]:
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
    encoder_path = source.parent / f"{prefix}encoder.onnx"
    hifigan_path = source.parent / f"{prefix}hifigan.onnx"
    onnx.save(encoder, encoder_path)
    onnx.save(hifigan, hifigan_path)
    return encoder_path, hifigan_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--fixture", type=Path)
    args = parser.parse_args()
    paths = split(args.model_dir / "vits_fp32.onnx", "split-")
    manifest = {"schema_version": 1, "format": "genie_v2proplus_split_v1", "boundary": BOUNDARY,
                "source_sha256": hashlib.sha256((args.model_dir / "vits_fp32.onnx").read_bytes()).hexdigest(),
                "graphs": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}}
    (args.model_dir / "split-vits.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    if args.fixture:
        encoder, hifigan = split(args.model_dir / "vits-parity.onnx", "parity-split-")
        replay = json.loads((args.fixture / "replay.json").read_text(encoding="utf-8"))
        tensors = {name: np.fromfile(args.fixture / item["file"], dtype=item["dtype"]).reshape(item["shape"])
                   for name, item in replay["tensors"].items()}
        options = ort.SessionOptions()
        options.intra_op_num_threads = 6
        session = ort.InferenceSession(str(encoder), sess_options=options, providers=["CPUExecutionProvider"])
        latent = session.run(None, {i.name: tensors[i.name] for i in session.get_inputs()})[0]
        del session
        latent.astype("<f4").tofile(args.fixture / "hifigan-latent.bin")
        replay["tensors"][BOUNDARY] = {"file": "hifigan-latent.bin", "dtype": "<f4", "shape": list(latent.shape)}
        session = ort.InferenceSession(str(hifigan), sess_options=options, providers=["CPUExecutionProvider"])
        audio = session.run(None, {BOUNDARY: latent, "ge": tensors["ge"]})[0]
        audio.astype("<f4").tofile(args.fixture / "python_split_audio.bin")
        reference = np.fromfile(args.fixture / "python_replay_audio.bin", dtype="<f4")
        error = float(np.max(np.abs(audio.reshape(-1) - reference)))
        if error > 1e-6 + np.max(np.abs(reference)) * 1e-3:
            raise ValueError(f"CPU split changed acoustic output: {error}")
        (args.fixture / "hifigan-replay.json").write_text(json.dumps(replay, indent=2) + "\n", encoding="utf-8")
        print(f"CPU split max_abs={error}, latent_shape={latent.shape}")
    print(json.dumps(manifest, indent=2))
