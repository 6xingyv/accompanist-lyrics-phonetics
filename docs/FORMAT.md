# LPD version 1

All fixed-width integers are little-endian. No serialized Kotlin objects or runtime-built index is present.

Header: 16 signed int32 fields (64 bytes): magic `0x3144504c` (ASCII LPD1), format=1, ReadingLocale ordinal (CN=0/TW=1/HK=2/JA=3), surface count, variant count, maximum UTF-16 key length, block count, block-index offset, keys offset, variants offset, string-pool offset, matrix offset, right context count, left context count, exact file size, reserved=0. Ordinal assignments are part of this format and may not change without a format version bump.

Block index: one int32 relative key-section offset per block. Keys are ordered by Kotlin UTF-16 lexical comparison, not Unicode code point order. Every 32nd key starts with an empty previous key; other keys share a prefix with their preceding key. Each key stores unsigned base-128 varints: prefix UTF-16 count, suffix count; suffix as uint16 units; variant count; first variant index. Shared prefixes may terminate between surrogate units; decoding reconstructs the complete key before any source alignment is considered.

Variants: fixed 20 bytes: reading string-pool offset (int32), comma-separated source-ID pool offset (int32), lexical cost (int32), left context ID (uint16), right context ID (uint16), scalar-aligned flag (int32, 0 or 1). De-duplication combines identical reading/context/alignment rows using their minimum cost and sorted union of source IDs.

String pool: deduplicated UTF-8 strings, each prefixed with an unsigned base-128 byte-length varint. Canonical tonal readings contain space-separated lowercase ASCII Pinyin/Jyutping syllables followed by a digit. Pinyin uses 1–4/neutral 5, `v` for ü and `e^` for ê; Cantonese uses Jyutping 1–6. Japanese readings are normalized hiragana with long-vowel signs; particle pronunciations use the IPADIC pronunciation field. Other lexical readings use the reading field. Romanization is absent from Japanese dictionaries.

Matrix: right-count × left-count signed int16 values, indexed `[previousRight * leftCount + nextLeft]`. Context ID 0 is BOS/EOS. Non-morphological packs have an empty matrix and zero connection costs. No floating-point scoring is used.

CSV and connection-matrix field semantics follow the upstream [dictionary format documentation](https://taku910.github.io/mecab/dic-detail.html); the resolver and binary compiler are independently implemented.

Compiler output is deterministic under input reordering. `compiled-packs.tsv` contains exact counts, lengths and SHA-256; normal release checks validate those checksums. Runtime validates fixed-section bounds on opening and variable-section bounds on access. LPD is a trusted compiled data format, not an untrusted arbitrary network dictionary format; full checksums are verified during acquisition/build/release rather than scanning mmap data on each parse.
