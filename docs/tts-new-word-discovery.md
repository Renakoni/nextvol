# New-word discovery

Algorithm 100 uses the original MultiTTS P6 incremental statistics. The default
batch route implements `Ngr.discovery(file, 2, 5, 1000, "")`; the optional extended
route implements its HanLP discovery and chunk merge. These are three distinct
algorithms. The NER test retains algorithm 101's existing semantics; the separate
new-word test is a NextVol addition for inspecting algorithm 100's actual output.

The maintained Kotlin implementation does not load the original `libngr.so` or
add a HanLP dependency. Discovery returns candidates only. It does not create
characters, select voices, overwrite manual bindings or mutate existing pairs.
Those responsibilities remain with the consuming role/text-analysis pipeline.

## Pure algorithm interfaces

All three classes are in `indi.renakoni.nextvol.tts.names`:

```kotlin
RealtimeNameDiscoverer(regex: String, stopwords: Set<String>)
    .submit(text: String, reverseWindows: Boolean, checkCancelled: () -> Unit = {})
    // List<RealtimeNameCandidate>, each with name: String and score: Double

NativeNewWordDiscovery(stopwords: Set<String>)
    .discover(reader: Reader, onlyNew: Boolean, dictionary: Map<String, Int>,
        checkCancelled: () -> Unit = {})

HanlpNewWordDiscovery()
    .discover(reader: Reader, onlyNew: Boolean, dictionary: Map<String, Int>,
        checkCancelled: () -> Unit = {})
    // Both batch routes return List<NewWordCandidate(name: String, value: Int)>.
```

The batch value is a normalized score for Native and an occurrence count for
Hanlp. `SpeechNewWordDiscovery.analyze` retains the selected `NewWordAlgorithm`
alongside the candidates. Do not display a Native score as a frequency.

Pure batch calls leave their Reader open and keep no statistics across calls.
The service owns and closes its Reader, including cancellation/failure, and
loads the packaged resources. Cancellation is checked while reading blocks,
scanning long lines, matching patterns, traversing tries and sorting. P6 can be
partially updated when cancelled; its session discards that instance and rebuilds
from completed submissions instead of publishing a partial result.

## P6: incremental realtime statistics

The original entry point is `O6.f.e(String, O6.e)`; `P6.d(String, String)` owns
the compiled expression and two tries. `O6.e.BEFORE` reverses the matching-window
list; `AFTER` keeps its order. `reverseWindows = true` means BEFORE, not reversing
the text or a sequence of submitted text units.

Each submission advances the synthetic prefix through `^ _ - + . * # %` and
appends `$`. Each regex match produces a window starting one UTF-16 unit before
the match and ending up to three units after it. For each window, in order:

1. Update every substring of length 2 through 6 in forward and reverse tries.
2. Examine length-2-through-5 substrings with both neighbors present. Require a
   full regex match and reject any stopword prefix or suffix.
3. Compute `min(leftEntropy, rightEntropy) * log10(frequency)`, using natural-log
   neighbor entropies. Keep positive scores only.
4. Multiply by window priority 100, then 10, 1, 0.1 and so on.

Update and scoring are interleaved per window. Pre-counting the entire input
before scoring changes the algorithm. Priority restarts for each submission;
the tries and marker index remain with the instance. Scores have no fixed upper
bound and are not probabilities. Repeated names from different windows remain
separate candidates. Sorting retains the original `(second.score-first.score)`
integer-cast comparator, including its coarse/nontransitive comparisons.

String slicing is Java UTF-16. Kotlin's String `reversed()` delegates to
`StringBuilder.reverse()`, preserving the original surrogate-pair behavior.

For the four submissions `林逸来到门前。`, `林逸转身离开。`, `林逸笑着点头。`,
`林逸站在窗边。`, the original AFTER results are empty, then 林逸 with scores
20.86580927584611, 52.417127361976846 and 83.46323710338444. Submitting their
concatenation once returns three 林逸 entries with scores 2.086580927584611,
0.30369443862366347 and 0.03385594922926082. BEFORE on that concatenation returns
only the last score. Submission boundaries are therefore part of the contract.

`SpeechNewWordSession` owns one book/range, normally a chapter. Stable unit keys
reuse completed results when text and direction are unchanged. Replacing a unit
replays the ledger in its original submission order; it must not concatenate
the ledger. A changed book, range or rule clears the previous history. Callers
reset the session when stopping or changing algorithms. Preview creates a
separate discoverer. This explicit lifecycle implements #824 rather than the
original app's global cache, repeated-playback accumulation and stale Pattern.

Two related original defects are handled explicitly: an empty regex disables
discovery, and a nonempty expression matching the synthetic prefix uses window
start zero. The original unconditionally used `match.start()-1`, which gives
`substring(-1, ...)` in both cases. There is no extra regex-validation layer;
syntax errors still propagate to the caller. A null saved rule uses the original
Chinese default, while an explicitly empty rule stays empty.

## Default batch: source implementation of native Ngr

This route reads newline-delimited text as Unicode code points, splits the
original punctuation set and classic C-locale whitespace, and counts lengths
1 through 6 in bidirectional tries. It scores candidate lengths 2 through 5:

```text
mutualInformation * internalCharacterEntropy * frequency
    * min(leftNeighborEntropy, rightNeighborEntropy)
```

These entropies use log2. The mutual-information denominator is the number of
distinct ngrams. Critically, even the backward recursion looks up suffix counts
in the forward trie; replacing it with conventional PMI or backward lookup is
incompatible. Every 10,000 input lines and at EOF, the algorithm scores and
tail-filters the chunk, retains up to 10,000 candidates, adds their scores to
the accumulated map and clears its counting tries. The accumulated result is
sorted and tail-filtered again, limited to 1,000, then dictionary-filtered and
limited to 300 as in the original Java wrapper.

Tail filtering builds a bidirectional trie from the native stopwords, initially
at frequency 1,000,000, then inserts accepted candidates with scaled scores.
Prefix lookup returns the last traversed node's frequency only when traversal
cannot continue. Superword lookup follows the first child until a terminal
node. A zero-valued terminal is still terminal. This is not a general substring
containment filter.

The first child depends on the original C++ container. `NativeDiscoveryMap`
reproduces libc++ 18's default-load bucket/predecessor list and prime rehash
rules, with identity hashing for wchar_t and the original CityHash for short
little-endian wchar_t strings. Native stopwords retain initializer order; they
first pass through the original unordered-set insertion/iteration order before
being inserted into the filtering trie. The same container rules are used for
character entropy, the two intermediate candidate maps and accumulated
candidates. Neighbor entropy also preserves the original copy into a fresh
character-frequency map before summing. Newest-insertion order is not used as
a substitute.

The returned value follows the original operation order:

```text
max(0, trunc((100 / log2(bestScore * 100)) * log2(score * 100)))
```

There is no forced maximum of 100. `NativeDiscoveryLog2` retains fdlibm's base-two
argument reduction and compensated arithmetic: JVM `ln(x)/ln(2)` adds rounding
that changes a verified top score from 99 to 100. For the original overlapping
东方青岚/青岚 fixture, the correct results are 青岚99 and 东方青岚90.

The private score sorter reproduces the shipped libc++ 18 non-branchless
introsort, including median/ninther partitioning, insertion shortcuts and heap
fallback. Its comparator uses descending raw score. Preserving equal-score
order matters because subsequent tail filtering inserts accepted candidates
into another trie. A JVM stable sort or lexical tie-break changes that order.
The inspected locale path initializes from `locale::classic()`; application-wide
replacement of the C++ global locale was not exhaustively ruled out.

## Extended batch: HanLP

The fixed configuration is `NewWordDiscover(5, 0f, .5f, 100f, onlyNew)`.
Java Pattern's original punctuation/space/digit runs become NUL; substring
lengths and neighbors use UTF-16 units. Probabilities and entropy accumulation
retain the original float operations. A candidate needs minimum neighbor
entropy .5 and minimum split aggregation 100. The decompiler's apparent integer
division is not original behavior: DEX contains int-to-float and float division.

The original reader ends a chunk after raw line lengths exceed 340,000 UTF-16
units. Each chunk selects up to 300 candidates through a Java PriorityQueue
ranked by probability. The outer HashMap merge sums frequencies, then sorts by
frequency and returns 300. This route uses neither the P6 nor native stopword
table. `onlyNew` checks the actual bundled dictionary frequency; it is not a
hand-written list of common words.

## Resources and licenses

Original target: MultiTTS 1.8.3 (84), APK SHA-256
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`.
All files below are under `app/src/main/assets/speech/names`.

| File | SHA-256 |
| --- | --- |
| `p6-default-person-name-regex.txt` | `9a447d1dcca54f5d3cdef7f418c19947bd9af786c396fd595b26a938ce877e5f` |
| `p6-stopwords.txt` | `1d6aea92b058a552251e7ac4cc62458c97642b7e73e438c921717e2d5575d02c` |
| `native-stopwords.txt` | `a3fa04da03ca472cdfd640ac0070ea2914b7f4eafcef1647b383c21f90d80e67` |
| `dictionary-frequency.tsv.gz` | `0b38f6cdbbe2ffdc52f13be7ee3d1fd18c6470e54f454daa3dfc1b6d17fe4a45` |
| `LICENSE-libcxx.txt` | `539dd7aed86e8a4f12cbdd0e6c50c189c7d74847e4fecc64ce2c6ee3a01da38b` |
| `LICENSE-fdlibm.txt` | `0ede84ebcd93942e41dcb79195dd96e576688e6f4a524d01f40424b4ad1daef8` |

The 1,812-character regex is the Chinese `person_name_regex_dft` resource, also
compared character-for-character with the original running app's Resources.
The 1,396-entry P6 stopword asset retains original bytes. The 1,481-entry native
table was recovered in initializer order from `libngr.so`.

The dictionary reconstructs the original double-array tries: 85,585 core words
and 83,911 custom words produce 169,493 merged entries, with core entries taking
precedence. Each entry was round-tripped against the original trie lookup.
The decompressed TSV SHA-256 is
`81f84bd245a5a0451b951e998331e771d1f6bf31914e9d0051498771827ca168`;
gzip changes source storage only: AAPT expands this file to the APK asset
`speech/names/dictionary-frequency.tsv`, which the service opens as plain text.
Examples are 中国3358, 天气151, 艾莉丝1000, and no
entry for 林逸. Preserve initializer order when loading the native stopword set.

The container/hash and sorting helpers derive from LLVM libc++ 18.1.8 under
Apache-2.0 with LLVM exceptions. The logarithm derives from fdlibm
e_log2.c/k_log.h under the included Sun permission notice. These helpers require
no runtime dependency.

## Evidence and verification

REA static evidence includes:

- P6 pipeline: `ev_941982d66c1228da868b216c41a7b70828e0f86e013c6acd61933fdd0770f9d8`.
- Native scoring: `ev_786a53c2ac818327e8b97d5b63ea8ce6708c6a60d380142b8106abaff4fc5aa0`.
- Backward recursion's forward lookup: `ev_f298be746ff0fd719bfede701cd90a9af15d39e380517ab29c0311c1d59ef07d`.
- Native tail traversal and terminal flag: `ev_6acf0424597c816d6b3a5056be2cfc84403db43d9155aa59dfa758c4f1f43d5d`.
- Child insertion and rehash: `ev_45f848d7488266c77f2d580bbe8d8e167452a633cae791dba46b5c28cd313d49`,
  `ev_51ecff0ffc8cafba322494e861a38fe93314e8190066c0a19137d29cba2f6287`.
- Native short-string hashing: `ev_e99dc1dcc72ded32aa1edf467338e79cd94aaddb4c6981a40cb4381e2f74b86b`.
- Native introsort: `ev_956ce858b9a0ff7efbaf516b9ced89208fb82f2ea63ace8841b67fa77420bbeb`.
- Neighbor-frequency map copies: `ev_60ae1dc3c9d7914e875086a16758b6d2c4b38d0c4307ed40e48911aae5806693`.
- HanLP route: `ev_9be47c1f3b7dd0ef747d24b4b0674a11cb36cdeb2a554f71ecb2d66708e56f72`.

Controlled original-runtime observations are
`ev_15f5ad3d97f97066ee8570274fc1cbcb623f3172714c47b68c1950e3dfe2f80b` (P6),
`ev_06c769b5e26d9085734da2252468642aa45a2a59dba7200b44c5b27460077d3b`,
`ev_cb27c8698f0c6571e89b5c2750a7cd63cd806f1c1690fb6c943dcf32d491ce41`, and
`ev_febf353f4704c1947044170d475e2599e015f2bb663a86562fa0a6886fc2f0ba` (batch).
P6 instrumentation captured candidates before role selection and prevented
assignment/serialization; it did not test final voice selection.

An independent standalone JVM probe compiled the maintained production sources
and matched all 35 frozen observations: 6 P6 checkpoints, 13 native cases and
16 HanLP cases. Native integer scores were compared exactly, including 99/100.
After restoring the intermediate maps, neighbor-frequency copies and native
sort, a separate 13-case native comparison matched every candidate name and
integer value in complete list order, including all 107 candidates in the
reverse-lookup fixture. Equal raw-score ordering is included in that comparison.

An independent source-built libc++ 18.1.8 probe matched all 2,263 output lines:
1,652 word hashes, the complete original stopword-set iteration order, 330 integer
map update states and 280 string map update states. It used 32-bit wchar_t and
64-bit size_t, covered collisions, repeated updates and multiple rehashes, and
used headers byte-identical to the version used for this reconstruction.

A separate comparison against source-built libc++ 18.1.8 matched all 161 sorter
cases by complete element order, including equal scores, the 24/128 size
boundaries, nearly sorted and alternating inputs, subranges and forced heap
fallback. It invokes the production private sorter without copying its Kotlin
implementation into the probe.

Focused repository tests retain original nonempty fixtures and cover the P6
submission contract, confirmed regex boundary fix, native scoring/filtering and
reverse lookup, libc++ child order, HanLP float thresholds/chunk reset, cancellation
inside long lines and Reader ownership. Session tests cover replay/deduplication
separately. Repository tests run in CI; they were not executed locally.
The native regression retains only the two tied candidates affected by ordering
and the reverse-lookup score, rather than storing the complete 107-item result.

The installed full APK passed four service calls through `SpeechNewWordDiscovery`:
Native and HanLP each with `onlyNew` off and on. All candidate names and values
matched the frozen original observations. REA evidence
`ev_3856ff891f1dad71de11882f70bba400361007613eba7dcad899527dcff7b640`
also confirms that the service opens the packaged plain TSV and removes the
dictionary word 天气 while retaining 林逸. APK assembly, unit/device test source
compilation and Android lint passed. The settings and test screen were checked
in the full app at default and 1.6 font scale.
