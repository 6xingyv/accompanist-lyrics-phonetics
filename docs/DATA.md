# Data Sources and Licenses

Library code and original dictionary additions use Apache-2.0. Language data retains its upstream licenses.

| Source | Pack | License and retained materials |
| --- | --- | --- |
| Unihan 17 | Mandarin, Cantonese | [Unicode-3.0](../data/licenses/unicode/) |
| CedPane | Mainland Mandarin | Public-domain data; [Unlicense and original README](../data/licenses/cedpane/) |
| McBopomofo / libtabe | Taiwan Mandarin | [MIT](../data/licenses/mcbopomofo/) and [BSD-3-Clause](../data/licenses/libtabe/) |
| Rime Cantonese | Hong Kong Cantonese | [CC-BY-4.0](../data/licenses/rime-cantonese/) |
| IPADIC | Japanese | [NAIST/ICOT](../data/licenses/ipadic/) |
| Original additions | Mandarin, Japanese | [Apache-2.0](../LICENSE) |

Source revisions, URLs and hashes are recorded in [sources.lock.json](../data/sources.lock.json).
Compiled pack hashes are recorded in [compiled-packs.tsv](../data/compiled-packs.tsv).

Data is normalized, deduplicated and compiled into LPD packs. Japanese packs also contain IPADIC-derived grammatical metadata. Per-pack `NOTICE` files describe modifications and attribution.

Redistribute each pack with its `sources.json`, `NOTICE` and complete upstream licenses. These materials are included in the published resources; the language data is not relicensed under Apache-2.0.
