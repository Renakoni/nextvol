# Name classification for voice selection

`SpeechNameClassifier.classify` returns a male/female/unknown voice-selection hint
and its basis (model, male rule, female rule, or none). It does not create or update
characters, voice pools or pairs. #785 consumes this result after existing/manual
bindings; #809 uses the same contract for text analysis. The labels are model
predictions about names, not assertions about a character's attributes.

The model runs through the existing source-built ONNX Runtime and maintained JNI
adapter. The original closed `libngr.so` is neither shipped nor called.

## Model and resources

Assets come from MultiTTS 1.8.3 (84), APK SHA-256
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`.

| Asset | SHA-256 |
| --- | --- |
| `ngr_cn_model.onnx` | `64c05315508c6a1d887504d77737a7044375e10646520cbae5fc912a521e1b38` |
| `ngr_cn_word.txt` | `84e131d5439b3eeb86b0eb2d6c78807f36888aa65b6b660d47d80028bafd34f8` |
| `ngr_families.txt` | `ee7b7d95d613db83e26453e538a24cae0295bd09843d43c4600f0b280aedce5a` |

The first two files retain their original bytes. `ngr_families.txt` is the exact
compiled surname array recovered from the library's initializer: 1,764 entries,
1,758 unique, including one-, two- and three-character surnames. It is data, not a
replacement name-recognition algorithm.

`NgrEncoder` follows the original `get_first_name` and `word2vector` functions:

1. Remove the longest matching surname prefix (up to three characters), leaving
   at least one character. Keep the last three characters of the remaining name.
2. Look up three individual-character scores, then two adjacent-bigram scores.
   Missing positions/keys are zero. The vocabulary contains 149,307 token/float32
   scores; a known token with score zero still constitutes supported input.
3. Run `input_float` with shape `[1,5]` and read `output_label`, int64 `[1]`.
   As in MultiTTS, zero selects the female hint and nonzero the male hint. The
   original does not consume the model's probability output or apply a confidence
   threshold, and neither does this implementation.

JNI widens Java UTF-16 units individually rather than combining surrogate pairs.
Kotlin String/Char indexing preserves this behavior, including unknown surrogate
halves. The model is trained for Chinese names; recognition of a character or
token does not establish support for another language.

## Rules and explicit compatibility differences

REA confirms the original first returns existing voice pairs, then uses Ngr when
its UI locale allows Chinese inference. Its non-Chinese fallback checks the male
regex with a full match and otherwise chooses the female pool. The female regex
is compiled but discarded: raw smali contains no `move-result`/`sput` after its
`Pattern.compile`. The male Pattern is also created only once, so later preference
changes do not rebuild it.

This implementation preserves model priority. If no vocabulary token matches, or
the device has no supported native runtime, it checks the male rule, then the
female rule, using full matches. Male wins when both rules match. Chinese defaults come from the original
`male_name_regex_dft` and `female_name_regex_dft` resources referenced by
`root_preferences`; other locales default to empty. Saved values, including an
explicitly cleared rule, remain unchanged when the locale changes. Rules are evaluated from the current request; invalid expressions surface
as errors when that rule path is used.

The following differences are explicit:

- Retaining and using the female pattern fixes the confirmed original defect;
  current rule edits take effect immediately instead of retaining an old Pattern.
- Model support follows actual vocabulary coverage and runtime availability,
  rather than the app's interface language.
- No matching rule/evidence returns Unknown, as required by #825. The original
  forces a binary result: for example, an all-zero unknown-input vector predicts
  male. Unknown is a NextVol contract, not an original JNI return value.

On a model-capable device, missing/corrupt resources or an inference failure remain
errors; they are not silently converted to Unknown. `available` describes model
availability only. Rule-based classification remains usable on other devices.

## Evidence and validation

REA evidence establishes the Java routing and native implementation:

- Localized rule defaults: `ev_5649353b43375df1ab2855a86123626dc0813050884235c0d60495b192f45a48`.

- Pair/model/rule order: `ev_ae86be4bbac1a67e69f8cb2b8c8badedac77d41d098b34a22c4a52a817b1cfdc`.
- Discarded female regex in raw smali: `ev_dfaa217732f0f8930f435f507f7a343484004432e23ec39badaef0d809f35a55`.
- Surname processing: `ev_b97ddf72118100c3c7c09de6be7678bc78f6610229098752cb05088d8d314202`.
- Five feature positions: `ev_98a05e36d3279b4fc02e4f74b5c2a4b2ae6fd9ef53f6480539faae57f5fc2efb`.
- Native input/output: `ev_bd7efe9f8eeff38f30a04878cca1945cb84566df866c1e4de3aaa46eada7e83c`.
- UTF-16 widening: `ev_fbeb7be27481b886dcc220596e9247ca7fdd375d78dc449f35feb82a82d2959a`.
- Surname initialization and insertion loop: `ev_0cf8fcff44ad2eb8fea0540bb352bf205b5ad0bbcbf6de631556b9c1a75def95`,
  `ev_fa34799d7b405089e91b35440eff8a874a5d269b1e138a2726a04a0d4c2785bb`.

A controlled REA probe executed the original Ngr implementation
(`ev_7c04ad5115e68cca3de5c94f69ccd95b8a2241f86d8e82be9f68741aa3ec33ae`).
王小明 and 司马光 returned male; 林黛玉, 欧阳娜娜 and 赵敏 returned female.
贾宝玉 also returned female, while ordinary words 天气 and 桌子 returned male.
These limitations are preserved rather than hidden behind invented heuristics.
The reconstructed inputs and shipped model also ran on host ONNX Runtime with
matching labels for these cases.

An isolated MuMu probe compiled the maintained `NgrEncoder.kt` and `name_model.cc`
with the source-built runtime. All 12 original labels and all 12 independently
reconstructed feature vectors matched. Unknown Latin/emoji inputs retained the
original raw model label while correctly reporting no vocabulary support for the
NextVol fallback contract.

Focused JVM tests cover surname/feature positions, UTF-16 unknown units and known
zero scores. Android tests cover the bundled model's priority, changed rules,
full matches, conflict order, syntax errors, cancellation and budget recovery.

## Ownership and cancellation

The classifier is a process singleton. Thread-safe lazy initialization loads the
immutable vocabulary and surname set once, and loads the model only for the first
supported name. Later requests reuse those resources. This follows the original
realtime `O6.j.g` path, which creates its static Ngr instance only when absent.
The separate original batch path creates one instance for the whole batch and
releases it afterward; direct release references were confirmed by REA
`ev_904ead8a9a56973e6bad61887945ba4e8a42aa1d9ada3df7b41c473c64d439e1`.

The fixed packaged resources remain owned until process exit; installing updated
assets restarts the application process. There is no result cache or book state,
and rules remain request arguments, so edits take effect on the next request.
Cancellation is checked after preparation, after model loading and before returning
inference results. A cancelled inference finishes its single bounded five-feature
call without publishing a result or discarding resources used by other requests.

Fallback regex compilation and full matching run in the existing independent
isolated worker pool, without scripts or a host broker. Each nonempty rule has a
2.5-second execution budget; male and female rules remain sequential and stop at
the first match. Android service startup has the executor's separate 15-second
readiness budget. Cancellation or budget expiry retires the worker via its
termination Binder call and waits up to two seconds for Binder death. A live
retiring Binder cannot be reused. Successful workers can serve later names and
retain the pool's existing idle retirement; no additional pool or regex cache is
introduced. The classifier retains one built-in authority ticket for its process
lifetime. Syntax errors retain the rule field, pattern and error offset; budget
and worker failures identify the male/female field rather than returning Unknown.

This is a per-name rule budget, not a chapter processing budget. The classifier
adds no name-length limit. Existing transport limits still apply: 32 MiB of
serialized UTF-8 request data and 512 KiB per input packet (gzip when useful), plus
the worker's existing sampled allocation budget. These are not a fixed character
limit or a promise that every request below 32 MiB fits in worker memory.

REA rechecked `O6.j.g` for #841
(`ev_0c61ddc116819cc46a69b57c1a14401e700887b93c840b29eda9b5d86d282386`):
the original fallback directly calls `Pattern.matcher(name).matches()` with no
execution budget at that call site. A bounded probe on the specified MuMu device
(`127.0.0.1:16448`) ran `(a+)+$` against 40,000 `a` characters followed by `!`.
After thread interruption and a 500 ms wait, matching was still running and used
498 ms of process CPU during that wait. The input wrapper received one `toString`
call and zero `charAt` calls. The probe process was then killed. Thus neither
thread interruption nor a checking CharSequence establishes Android cancellation;
the isolated process boundary is necessary. This observation is independent of
the earlier host-JVM probe.

The corrected classifier was probed on the same MuMu (Android 12), using its real
Binder workers. Warm-worker timings include encoding, transport and matching:

| Input characters (`a` followed by `!`) | Linear `[a-z]+!` rule | Both original default rules |
| --- | --- | --- |
| 40,001 | 10 ms | 16 ms |
| 1,000,001 | 44 ms | 239 ms |
| 4,000,001 | 180 ms | 744 ms |

With the probe's 10-second budget, the high-backtracking female rule returned
`femaleNameRegex: Timeout` after 10,058 ms with its Binder already dead. Through the actual classification UI,
cancel, input edit and leaving the tab terminated the old workers in 83, 481 and
388 ms respectively, measured from the UI action to observed Binder death.
Each action was followed by a fresh successful classification without a stale
result. These are device observations, not universal latency guarantees. The
temporary measurement harness is not shipped or retained in the test suite.
The final 2.5-second budget is a constant-only adjustment; these device timings
were collected before that adjustment.
