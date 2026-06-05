# Yjs → Java Porting Conventions

This is a **faithful port of Yjs v13.6.31** (CRDT library) and the parts of **lib0 v0.2.99**
it depends on, plus a minimal **y-protocols/sync** for the test harness.

Reference sources (read-only, on this machine):
- Yjs:        `/tmp/yjs-stable/src/**`  and tests `/tmp/yjs-stable/tests/**`
- lib0:       `/tmp/lib0/**`
- y-protocols:`/tmp/y-protocols/src/sync.js`

**Goal:** behavior-identical CRDT. Byte-identical update/encoding output where Yjs is
deterministic (so cross-compat & golden tests pass). Bias to correctness and rigor.

## Package layout (mirrors Yjs)
- `dev.yjs.lib0`    — ported lib0 primitives (Encoder, Decoder, Binary, Buffer, etc.)
- `dev.yjs.structs` — AbstractStruct, Item, GC, Skip, AbstractContent, Content*
- `dev.yjs.types`   — AbstractType, YArray, YMap, YText, YXml*, YEvent subclasses, EventHandler
- `dev.yjs.utils`   — ID, StructStore, DeleteSet, Transaction, Doc, Snapshot,
                      RelativePosition, UndoManager, UpdateEncoder/Decoder, encoding, updates
- `dev.yjs`         — public facade `Y` (re-exports), shared interfaces

In Yjs everything is re-exported through `src/internals.js`. In Java, just import across
packages directly. Keep classes `public`. Free functions in Yjs become `public static`
methods on a relevant class or on a `*Util` / the owning class (see "Free functions" below).

## Core type mapping
| JS                         | Java                                                            |
|----------------------------|-----------------------------------------------------------------|
| `Uint8Array`               | `byte[]` (treat bytes as **unsigned**: `b & 0xFF` on read)      |
| `number` as clientID/clock | **`long`** (clientID is uint32; clock grows; 53-bit safe range) |
| `number` as length/offset/index/ref | **`int`**                                             |
| `number` as a stored value | `Long` (integral) or `Double` (non-integral) — see writeAny     |
| `string`                   | `String` (UTF-8 for byte length; see String notes)              |
| `boolean`                  | `boolean`                                                       |
| JS object `{}` (map)       | `java.util.Map<String,Object>` (insertion order: LinkedHashMap) |
| JS array `[]`              | `java.util.List<Object>` (ArrayList)                            |
| `Map`                      | `java.util.Map` (use `LinkedHashMap` when iteration order matters; Yjs Map preserves insertion order) |
| `Set`                      | `java.util.Set` (`LinkedHashSet` when order matters)            |
| `null` / `undefined`       | `null` (we generally collapse undefined→null; note exceptions)  |
| `===` on objects           | `==` (reference identity) — Items/types compared by identity    |
| `ID` equality              | `ID.compareIDs(a,b)` / `a.equals(b)` (value equality)           |

`ID`: `public final class ID { public final long client; public final long clock; }`
Implement `equals`/`hashCode` by value AND keep `compareIDs(a,b)` static (handles nulls,
returns true if both null-equal or same client+clock). Yjs uses `compareIDs`.

## Numbers & `writeAny`/`readAny` (lib0/encoding) — match JS exactly
`writeAny(enc, Object data)`:
- `String`           → tag 119, writeVarString
- number (Integer/Long/Short/Byte, or a Double/Float that is integer-valued):
  let `d` = double value. If `isInteger(d) && abs(d) <= 2^31-1` (`binary.BITS31`) →
  tag 125 `writeVarInt((long)d)`. Else if float32-representable → tag 124 writeFloat32.
  Else → tag 123 writeFloat64.
  (JS: `Number.isInteger(data) && Math.abs(data) <= BITS31`.)
- `Boolean`          → tag 120 (true) / 121 (false)
- `null`             → tag 126
- `byte[]`           → tag 116 writeVarUint8Array
- `List<?>`          → tag 117: writeVarUint(size) then each writeAny
- `Map<String,?>`    → tag 118: writeVarUint(size) then (writeVarString(key), writeAny(val))
- anything else / Java `null` reference for "undefined" cases → tag 127 (undefined)
- BigInteger (64-bit) → tag 122 writeBigInt64 (only if used)

`readAny` lookup table indexed by `127 - tag`:
- 127 undefined → `null` (we use null for undefined; see UNDEFINED note)
- 126 null → `null`
- 125 integer → `Long` (readVarInt)
- 124 float32 → `Double` (readFloat32 widened)
- 123 float64 → `Double`
- 122 bigint → `BigInteger`/`Long`
- 121 false → `Boolean.FALSE`, 120 true → `Boolean.TRUE`
- 119 string → `String`
- 118 object → `LinkedHashMap<String,Object>`
- 117 array → `ArrayList<Object>`
- 116 Uint8Array → `byte[]`

**UNDEFINED note:** JS distinguishes `undefined` (127) and `null` (126). A few places matter
(e.g. ContentAny may hold `undefined`). Default: map both to Java `null`. If a test needs the
distinction, introduce `dev.yjs.Undefined.UNDEFINED` sentinel — only where required.

## lib0/encoding negative-zero trick (CRITICAL for round-trip)
`writeVarInt` uses the sign bit even for value 0 ("−0") to flag repeats in the OptRle encoders.
Java `long` cannot represent −0, so:
- Implement `Encoding.writeVarInt(Encoder e, long num)` for the normal case (isNegative = num<0).
- Add `Encoding.writeVarIntSigned(Encoder e, long magnitude, boolean negative)` (magnitude>=0)
  used by the OptRle flush: count==1 → negative=false; count>1 → negative=true; magnitude=s.
- For decode, the OptRle decoders must recover the sign bit even when magnitude==0. Implement
  `Decoding.readVarInt(Decoder d)` returning `long` (normal), AND a way to detect the sign bit:
  e.g. `Decoding.readVarIntResult(d)` returning a small holder `{ long value; boolean sign; }`
  (value is the **signed** value with magnitude; `sign` = sign-bit-was-set, true also for −0).
  UintOptRleDecoder/IncUintOptRleDecoder use `sign` to decide whether a count follows.
`math.isNegativeZero(n)` semantics = "sign bit set" = `n < 0 || (n==0 && 1/n<0)`. In Java terms
operate on the explicit `sign` boolean from the decoder.

## Endianness & floats
- varUint/varInt: little-endian 7-bit groups (port loop exactly; use `Math.floor(x/128)` style,
  but with `long` you can use `>>>` shifts safely for non-negative).
- writeFloat32/64, writeBigInt64: **big-endian** (`DataView.setFloat32(0,n,false)`); use
  `ByteBuffer` default (BIG_ENDIAN) or bit ops. readFloat* big-endian likewise.

## Strings
- `writeVarString`: writeVarUint(utf8ByteLength) then the UTF-8 bytes. Use
  `s.getBytes(StandardCharsets.UTF_8)`.
- `readVarString`: read len bytes, `new String(bytes, UTF_8)`.
- Yjs string lengths in YText are **UTF-16 code unit** based (JS `.length`). Java `String.length()`
  is also UTF-16 code units — GOOD, they match. Use `String.length()`/`substring`/`charAt`
  for YText splicing (NOT codePoint counts). `splitItem` on ContentString splits by UTF-16 units.

## Bit flags / `info` byte
Use `int` with masks from `dev.yjs.lib0.Binary` (BIT1=1, BIT2=2, ... BIT8=128; BITS5=31, etc.).

## Free functions
Yjs has many module-level functions (e.g. `getState`, `addStruct`, `createID`,
`getItemCleanStart`, `writeStateAsUpdate`, ...). In Java, put each as a `public static`
method on the most natural owner class and reference it statically:
- `createID`, `compareIDs`, `writeID`, `readID`, `findRootTypeKey` → `ID`
- `getState`, `getStateVector`, `addStruct`, `getItem`, `getItemCleanStart`,
  `getItemCleanEnd`, `findIndexSS`, `replaceStruct`, `integrityCheck`,
  `cleanupStructs`, `readAndApplyDeleteSet`(no)… → `StructStore`
- delete-set ops (`addToDeleteSet`, `isDeleted`, `mergeDeleteSets`, `writeDeleteSet`,
  `readDeleteSet`, `createDeleteSetFromStructStore`, `equalDeleteSets`, ...) → `DeleteSet`
- `transact`, `tryGc`, `addChangedTypeToTransaction`, `writeUpdateMessageFromTransaction`,
  `cleanupTransactions`, `nextID` → `Transaction`
- struct/content readers (`readContentAny`, etc.) → static on each Content class; the
  dispatch table `contentRefs` lives on `Item` (or a `ContentRefs` class).
- `redoItem`, `keepItem`, `splitItem`, `followRedone`, `readItemContent` → `Item` (static)
- encode/decode update entry points (`encodeStateAsUpdate(V2)`, `applyUpdate(V2)`,
  `mergeUpdates(V2)`, `encodeStateVector`, `diffUpdate`, `logUpdate`, ...) → `Updates`
  (class in utils) which the `Y` facade re-exposes.

The `Y` facade (`dev.yjs.Y`) exposes the public API as static methods/constants mirroring
`src/index.js`, delegating to the above. Tests should be portable nearly 1:1 against `Y`.

## Encoders/Decoders (UpdateEncoderV1/V2, DSEncoderV1/V2)
Port `src/utils/UpdateEncoder.js` and `UpdateDecoder.js` as classes implementing common
interfaces. Methods: `writeLeftID/writeRightID/writeClient/writeInfo/writeString/
writeParentInfo/writeTypeRef/writeLen/writeAny/writeBuf/writeJSON/writeKey` (+ resetDsCurVal,
writeDsClock, writeDsLen) and the decoder mirrors. V2 uses the Rle/OptRle/IntDiff encoders
from lib0. Keep the exact field encoders per V2 (see JS).

## AbstractType events & EventHandler
`EventHandler<ARG0,ARG1>` holds listeners `BiConsumer`-like. Yjs callbacks have varying arity;
define functional interfaces as needed (e.g. `YEventsCallback`, `UpdateCallback`).
Observers: `observe`, `unobserve`, `observeDeep`, `unobserveDeep`. `_eventHandler`,
`_deepEventHandler` on AbstractType.

## Equality in tests
Port lib0 `t.compare` deep-equality semantics: structural equality for List/Map/byte[]/
numbers/strings/booleans/null and for Y types compare `toJSON()`. Implement in
`dev.yjs.test.TestHelper`/assertions. Numbers: treat `Long`/`Integer`/`Double` with equal
mathematical value as equal in deep-compare (since JS has one number type). byte[] compared
element-wise.

## PRNG (tests)
Port `Xorshift32` and `Xoroshiro128plus` from lib0 exactly (32-bit ops via Java `int`, expose
unsigned via `& 0xFFFFFFFFL`). `PRNG.next()` returns `double` in [0,1). Port prng helpers
(bool,int32,int53,oneOf,word,char,letter,uint8Array). Seed is a fixed/explicit int per test
(reproducible). Convergence is the invariant; exact JS sequence match is a bonus.

## Testing framework
Map lib0/testing onto JUnit 5. Provide `dev.yjs.test.T` with `assert`, `assertTrue`,
`compare(a,b)`, `compareArrays`, `fail`, `compareStrings`, `groupAsync` (sync ok), and a
`TestCase` carrying a `PRNG`. Port `tests/testHelper.js` to `dev.yjs.test.TestHelper`
(TestConnector, TestYInstance, init, compare, applyRandomTests). Port `y-protocols/sync`
(writeSyncStep1/2, writeUpdate, readSyncMessage, messageYjsSyncStep1=0/Step2=1/Update=2) to
`dev.yjs.test.SyncProtocol`.

Each `tests/*.tests.js` → `src/test/java/dev/yjs/<Name>Test.java` (JUnit). Keep test method
names recognizable (e.g. `testBasicMap` ~ `testMapBasic`). Deterministic tests assert exact
behavior; `applyRandomTests`-based tests run a fixed number of iterations with a fixed seed.

## Misc
- `error.methodUnimplemented()` → `throw new UnsupportedOperationException("method unimplemented")`
- `error.unexpectedCase()` → `throw new IllegalStateException("Unexpected case")`
- `map.setIfUndefined(m,k,fn)` → `m.computeIfAbsent(k, kk -> fn.get())`
- `array.last(a)` → `a.get(a.size()-1)`
- Doc.clientID default: `random.uint32()` → `Math.abs(new java.util.Random().nextInt()) ...`
  Actually generate a non-negative long < 2^32: `(long)(Math.random()*0x100000000L)`; tests set
  clientID explicitly so determinism is fine.
- Keep method/field names identical to JS where possible (`_start`, `_map`, `_length`,
  `_item`, `_searchMarker`, `parentSub`, `rightOrigin`, `lastId`, `info`, `redone`, `keep`).
  For JS getters/setters (`get deleted()/set deleted()`) use Java methods `deleted()` /
  `setDeleted(boolean)` OR keep field + helper; prefer explicit methods to avoid confusion.
