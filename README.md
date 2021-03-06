## onyx-amazon-s3

Onyx plugin for Amazon S3.

#### Installation

In your project file:

```clojure
[org.onyxplatform/onyx-amazon-s3 "0.12.7.0"]
```

#### Functions

##### Input Task

In your peer boot-up namespace:

```clojure
(:require [onyx.plugin.s3-input])
```

Catalog entry:

```clojure
{:onyx/name task-name
 :onyx/plugin :onyx.plugin.s3-input/input
 :onyx/type :input
 :onyx/medium :s3
 :onyx/batch-size 20
 :onyx/max-peers 1
 :s3/bucket "mybucket"
 :s3/prefix "filter-prefix/example/"
 :s3/deserializer-fn :my.ns/deserializer-fn
 :s3/buffer-size-bytes 10000000
 :onyx/doc "Reads segments from keys in an S3 bucket."}
```

Lifecycle entry:

```clojure
{:lifecycle/task <<TASK_NAME>>
 :lifecycle/calls :onyx.plugin.s3-input/s3-input-calls}
```

#### Attributes

|key                           | type      | description
|------------------------------|-----------|------------
|`:s3/bucket`                  | `string`  | The name of the s3 bucket to read objects from.
|`:s3/deserializer-fn`         | `keyword` | A namespaced keyword pointing to a fully qualified function that will deserialize from bytes to segments. Currently only reading from newline separated values is supported, thus the serializer must deserialize line by line.
|`:s3/prefix`                  | `string`  | Filter the keys to be read by a supplied prefix.
|`:s3/file-key`                | `string`  | When set, includes the S3 key of file from which the segment's line was read under this key.

##### Output Task

In your peer boot-up namespace:

```clojure
(:require [onyx.plugin.s3-output])
```

Catalog entry:

```clojure
{:onyx/name <<TASK_NAME>>
 :onyx/plugin :onyx.plugin.s3-output/output
 :s3/bucket <<BUCKET_NAME>>
 :s3/encryption :none
 :s3/serializer-fn :my.ns/serializer-fn
 :s3/key-naming-fn :onyx.plugin.s3-output/default-naming-fn
 :s3/prefix "filter-prefix/example/"
 :s3/prefix-separator "/"
 :s3/serialize-per-element? false
 :s3/max-concurrent-uploads 20
 :onyx/type :output
 :onyx/medium :s3
 :onyx/batch-size 20
 :onyx/doc "Writes segments to s3 files, one file per batch"}
```

Segments received by this task must be serialized to bytes by the `:s3/serializer-fn`,
into a file per batch, placed at a key in the bucket which is named via the
function defined at `:s3/key-naming-fn`. This function takes an event map and
returns a string. Using the default naming function, `:onyx.plugin.s3-output/default-naming-fn`,
 will name keys in the following format in UTC time format:
 "yyyy-MM-dd-hh.mm.ss.SSS_batch_BATCH_UUID".

You can define `:s3/encryption` to be `:aes256` if your S3 bucket has
encryption enabled. The default value is `:none`.

When `:s3/serialize-per-element?` is set to true, the serializer will be called
on each individual segmnt, rather than the whole batch, and will be separated
by the string value set in `:s3/serialize-per-element-separator`.

An alternative upload mode can be selected via `:s3/multi-upload` documented below. 
When this option is used segments will be partitioned into different objects via a grouping key.

Lifecycle entry:

```clojure
{:lifecycle/task <<TASK_NAME>>
 :lifecycle/calls :onyx.plugin.s3-output/s3-output-calls}
```

#### Attributes

|key                                    | type      | description
|---------------------------------------|-----------|------------
|`:s3/bucket`                           | `string`  | The name of the s3 bucket to write to
|`:s3/serializer-fn`                    | `keyword` | A namespaced keyword pointing to a fully qualified function that will serialize the batch of segments to bytes
|`:s3/key-naming-fn`                    | `keyword` | A namespaced keyword pointing to a fully qualified function that be supplied with the Onyx event map, and produce an s3 key for the batch.  
|`:s3/prefix`                           | `string`  | A prefix to prepend to the keys generated by `:s3/key-naming-fn`.
|`:s3/prefix-separator`                 | `string`  | A separator to add after `:s3/prefix` and before the result of `:s3/key-naming-fn`. Defaults to "/".
|`:s3/multi-upload`                     | `boolean` | Flag that causes the plugin to group the batch of segments by `:s3/prefix-key`, and upload an object per group.
|`:s3/prefix-key`                       | `any`     | Used with `s3/multi-upload`. Key to batch segments into prefixed objects via e.g. `[{:a 3 :k "batch1"}{:a 2 :k "batch2"}]` will cause two objects to be uploaded under prefix "batch1" and "batch2".
|`:s3/content-type`                     | `string`  | Optional content type for value
|`:s3/encryption`                       | `keyword` | Optional server side encryption setting. One of `:sse256` or `:none`.
|`:s3/region`                           | `string`  | The S3 region to write objects to.
|`:s3/endpoint-url`                     | `string`  | The S3 endpoint-url to connect to (for S3-compatible storage solutions).
|`:s3/max-concurrent-uploads`           | `integer` | Maximum number of simultaneous uploads.
|`:s3/serialize-per-element?`           | `boolean` | Flag for whether to serialize as an entire batch, or serialize per element and separate by newline characters.
|`:s3/serialize-per-element-separator`  | `string` | String to separate per element strings with. Defaults to newline charactor.

#### Acknowledgments

Many thanks to [AdGoji](http://www.adgoji.com) for allowing this work to be open sourced and contributed back to the Onyx Platform community.

#### Contributing

Pull requests into the master branch are welcomed.

#### License

Copyright © 2017 Distributed Masonry LLC

Distributed under the Eclipse Public License, the same as Clojure.
