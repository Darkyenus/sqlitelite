# Android SQLite-lite

This library bundles a custom build of SQLite native library with custom Java API, which is lighter and faster than built-in Android SQLite APIs.

The library started as a fork of [sqlite-android from Requery](https://github.com/requery/sqlite-android), which I recommend if you need the standard Android API.
However, the whole Java API has been rewritten and most of the native binding code have been changed since.

## Benefits and considerations

Primary goal is to use a consistent and up-to-date SQLite on each Android version, so there is  need to cater to the lowest supported version. This makes compatibility problems less likely and reduces the work needed to develop and test your application.

It is not a goal of this project to be interoperable with existing Android libraries that depend on the default or support SQLite API. Use [sqlite-android from Requery](https://github.com/requery/sqlite-android) if that is something you need. This makes it possible to reduce the API surface, simplifying the implementation and removing constraints which are rarely useful and only slow down and bloat the program in most cases.

The combination of updated SQLite with an API which is much closer to the API of SQLite (but not necessarily low level), it is possible to achieve much higher performance in workloads which suffer from API overhead. Benchmark results speak for themselves:

```
Read 100 000 rows with a single cursor (each entry contains LONG, short String and 800 byte BLOB)   
  Android:       0.47 reads/second
  Requery:       0.49 reads/second
    Light:       2.32 reads/second

Read 100 000 rows with a single cursor (each entry contains LONG, short String and 3 byte BLOB)
  Android:       1.96 reads/second
  Requery:       0.95 reads/second (there is a performance problem (by design) in Requery's Cursor implementation, that causes O(n^2) behavior which appears when iterating through large Cursor results)
    Light:       3.03 reads/second

Each transaction writes 10 rows of two LONGs each  
   Android:    3295.69 transactions/second
   Requery:    5607.22 transactions/second
     Light:   40171.91 transactions/second
```

The benchmark code is [here](src/androidTest/java/com/darkyen/sqlite/DatabaseBenchmarkTest.java).

The benchmarks are constructed so that they measure performance of the bindings, rather than of SQLite itself, which fits some workloads better than others.
Workloads that consist of many repeated small queries will benefit much more than those that perform only a few complex ones. There shouldn't be any workload that results in a slowdown compared to vanilla Android or Requery, but I will be happy to be proven otherwise.

## Usage

Install from [jitpack](TODO).

There are only three classes of the API:
- `SQLiteDelegate`
  - Contains DB settings and versioning callbacks, very similar to the standard `SQLiteOpenHelper` class
- `SQLiteConnection`
  - This wraps the `sqlite3*` of the native API. Android SQLite API manages these as a part of a connection pool, then further wraps them in sessions that handle transactions and their nesting. This library has no connection pools and no transaction nesting. You can still create multiple connections to the same database and even put them into a pool if you want. One connection can be used by only one thread at the same time, but is not bound to the thread (unlike Android's API which uses thread locals).
  - You can run one-off SQL statements here (`CREATE`s, `DROP`s, `PRAGMA`s, etc.) and begin/end transactions
  - You can create `SQLiteStatement` (=prepared statement) from here - those are used for all data manipulation tasks (`INSERT`, `SELECT`, `UPDATE`, `DELETE`, etc.)
  - Don't forget to close the connection when you are done with it (or don't, if you plan to keep using it until your app dies)
- `SQLiteStatement`
  - Corresponds to SQLite's `sqlite3_stmt*` and Android's `SQLiteStatement` + `SQLiteQuery` + `Cursor`
  - You can bind query parameters here, run one-time inserts/updates/queries and use it as a cursor
  - While cursor is being iterated, it is not possible to change bindings and use other execute methods
  - If you keep the statement around with the database connection, you don't need to close it - it will get closed automatically when you close the database. However, if you only need it for one-time command, close it (try-with-resources works well here). Otherwise, you will leak both Java and native memory.

The library does not try to catch any memory leaks. But it is not hard to keep track of everything, there are only two classes with a lifetime and if you get hold of any, it is your job to close them when you no longer need them. Not closing them will not lead to data loss, just to a memory leak.

Closing is idempotent - closing something multiple times is a no-op.

The API design is very close to the API design of SQLite itself. Familiarity with it will help, but is not required to use this library.

It should not be possible to break anything through API misuse, because SQLite will catch it, report an error and that error will appear as an `SQLiteException`.

## CPU Architectures

Whole AAR is about 1.5 MB. Each of the four built-in CPU architectures is around 750 kB.

You can exclude certain architectures by using `packagingOptions`:

```gradle
android {
    packagingOptions {
        exclude 'lib/armeabi-v7a/libsqlite3l.so'
        exclude 'lib/arm64-v8a/libsqlite3l.so'
        exclude 'lib/x86/libsqlite3l.so'
        exclude 'lib/x86_64/libsqlite3l.so'
    }
}
```

Google Play may have additional requirements on which architectures must be present,
but if you are publishing to Google Play, you can use [App Bundle distribution](https://developer.android.com/guide/app-bundle) which solves the problem optimally.

## Requirements

The min SDK level is API level 21 (Android 5.0 Lollipop). It might be possible to lower this further, I just didn't need it.

## Versioning

The library is versioned after the version of SQLite it contains. For changes specific to just the
wrapper API a revision number is added e.g. 3.40.1.X, where X is the revision number.

## Acknowledgements

The project is based on [sqlite-android from Requery](https://github.com/requery/sqlite-android), which in turn is based on the AOSP code and the [Android SQLite bindings](https://www.sqlite.org/android/doc/trunk/www/index.wiki).

## License

    Copyright (C) 2023 Jan Polák
    Copyright (C) 2017-2022 requery.io
    Copyright (C) 2005-2012 The Android Open Source Project

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
