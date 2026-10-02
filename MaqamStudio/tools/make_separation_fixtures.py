#!/usr/bin/env python3
"""Writes two tiny Core ML models used by the separation tests.

The app accepts any Core ML model that follows its separation contract
(see the README, "Vocal separation"):

  input  "magnitudes"  Float32 [1, 256, 2049]  mid-channel STFT magnitudes
  output "vocal_mask"  Float32 [1, 256, 2049]  0..1, how much of each bin is voice
  metadata "maqam.sample_rate" (optional, default 44100)

These fixtures are not separators. One follows the contract and answers 0.5
everywhere, so the tests can check that a model's masks reach the stems; the
other names its input wrongly, so the tests can check it is refused with a
reason. Needs coremltools (pip install coremltools); runs on Linux or macOS.
The results are committed, so CI does not need coremltools.
"""
import pathlib

import coremltools as ct
from coremltools.models import datatypes
from coremltools.models.neural_network import NeuralNetworkBuilder

FIXTURES = pathlib.Path(__file__).resolve().parent.parent / "Tests" / "Fixtures"
SHAPE = (1, 256, 2049)


def model(input_name: str, output_name: str, path: pathlib.Path) -> None:
    builder = NeuralNetworkBuilder([(input_name, datatypes.Array(*SHAPE))], [(output_name, datatypes.Array(*SHAPE))],
                                   disable_rank5_shape_mapping=True)
    # y = 0 * x + 0.5
    builder.add_activation("mask", non_linearity="LINEAR", input_name=input_name, output_name=output_name,
                           params=[0.0, 0.5])
    spec = builder.spec
    spec.description.metadata.shortDescription = "Maqam Studio test fixture: half of every bin is voice."
    spec.description.metadata.userDefined["maqam.sample_rate"] = "44100"
    # Not .mlmodel, so Xcode copies it as a plain resource instead of compiling it.
    path.write_bytes(spec.SerializeToString())
    print(f"wrote {path.relative_to(FIXTURES.parent.parent)} ({path.stat().st_size} bytes)")


model("magnitudes", "vocal_mask", FIXTURES / "separation_half_mask.coremlspec")
model("spectrogram", "vocal_mask", FIXTURES / "separation_wrong_input.coremlspec")
