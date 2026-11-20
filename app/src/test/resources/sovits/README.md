# Small checkpoint fixtures

`gpt`, `v2pro` and `v2proplus` contain the supported architecture metadata and a
two-element FP32 tensor `[1.0, -2.0]`, written with `torch.save`. They exercise
the real ZIP/pickle reader and conversion with a small test graph manifest.
`v5` adds the diffusion tensor key that must be rejected before template access.

The protocol 2/4 fixtures come from the same author's Voicebox checkpoint tests
(commit `7963c3511b92271bab89f5dacd39eb4a3ab71b9a`). They include FP16, FP32,
BF16, offset and non-contiguous tensors. These are test data, not speech models.
