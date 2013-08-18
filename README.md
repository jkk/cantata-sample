# Cantata Sample

A playground sample project for the Cantata Clojure library.

It uses an adapted version of the Sakila database from MySQL, which models a movie rental store:

<img src="https://github.com/jkk/cantata-sample/raw/master/doc/film_store.png">

## Usage

Clone the repo. Launch a Clojure REPL for the project according to your tastes. Then:

```clj
(require '[cantata.core :as c])
(require '[cantata-sample.core :refer :all])
(import-data! ds)

;; Example
(c/query ds [:from :film :where [:= "ACADEMY DINOSAUR" :title]])
```

For more queries to try, see [core.clj](https://github.com/jkk/cantata-sample/blob/master/src/cantata_sample/core.clj).

## License

Original Sakila database schema and data licensed under the New BSD license - http://www.opensource.org/licenses/bsd-license.php

Changes made to Sakila:

* Removed `last_update` field from all tables
* Removed `picture` field from staff
* Created a `store_manager` table to avoid cyclic dependency between store and staff
* Renamed all PKs to `id`
* Culled rows by about 50%

Code copyright © 2013 Justin Kramer

Distributed under the New BSD license - http://www.opensource.org/licenses/bsd-license.php