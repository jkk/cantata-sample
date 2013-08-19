# Cantata Sample

A playground sample project for the [Cantata](https://github.com/jkk/cantata) Clojure library.

It uses an adapted version of the Sakila database from MySQL, which models a movie rental store:

<img src="https://github.com/jkk/cantata-sample/raw/master/doc/film_store.png">

## Setup

Clone the repo. Launch a Clojure REPL for the project according to your tastes. Then:

```clj
(require '[cantata.core :as c])
(require '[cantata-sample.core :refer :all])
(import-data! ds)
```

By default, an embedded H2 database is used. MySQL and PostgreSQL schemas are also included but require databases to be set up:

MySQL:

```
$ mysql
mysql> create database film_store;
mysql> grant all on film_store.* to 'film_store'@'localhost' identified by 'film_store';
```

PostgreSQL (when prompted, enter "film_store" (without quotes) as password):

```
$ createuser -P film_store
$ createdb -O film_store film_store
```

## Things to Try

```clj
;; Basics
(c/query ds {:from :film :limit 1})
(c/queryf ds [:select :film.actor :where [:= 1 :id]])
  
;; Queries are simple data. They can be built up like any other.
(def full-film
  {:from :film
   :include [:category :actor :language :original-language]})
  
;; Single film with nested related info - single DB query
(c/query ds [full-film :where [:= :id 123]])
  
;; Same results - 3 DB queries (an extra for each to-many relationship)
(c/querym ds [full-film :where [:= :id 123]])
  
;; See the SQL being executed
(c/verbose
  (c/querym ds [full-film :where [:= :id 123]]))
  
;; Multiple films with related info. Also backed by 3 DB queries.
(c/querym ds [full-film :limit 10])
  
;; Title and language of films rented by customers living in Canada.
;; 7 implicit joins performed.
(c/query
  ds [:from :film
      :select [:title :language.name]
      :where [:= "Canada" :renter.country-name]])
  
;; Prepared queries, with bindable parameters
(let [pq (c/prepare-query
           ds [:from :film
               :where [:= :?country :renter.country-name]])]
  (c/query ds pq :params {:country "Canada"}))
  
;; The above query is equivalent to:
"SELECT f.film_id, f.title, lang.name
 FROM film AS f
 LEFT JOIN inventory AS i ON f.film_id = i.film_id
 LEFT JOIN rental AS r ON i.inventory_id = r.inventory_id
 LEFT JOIN customer AS cust ON r.customer_id = cust.customer_id
 LEFT JOIN address AS addr ON cust.address_id = addr.address_id
 LEFT JOIN city ON addr.city_id = city.city_id
 LEFT JOIN country ON city.country_id = country.country_id
 LEFT JOIN language AS lang ON f.language_id = lang.language_id
 WHERE country.country = 'Canada'"
  
;; Fetch by id
(c/by-id ds :film 1 [:include [:category :language]])
  
;; Predicates (such as the :where clause) are also simple data
(def kid-film {:from :film
               :where [:and
                       [:in :rating ["G" "PG"]]
                       [:< 90 :length 100]]})
(c/query ds [kid-film :limit 5])
  
;; Without nesting - single DB query with redundant values
(c/query ds [:from :film
             :select [:id :title]
             :include :actor
             :where [:= 1 :id]]
         :flat true)
  
;; Count of films that have never been rented
(c/query-count ds [:from :film :without :rental])
  
;; Plain SQL
(c/query ds "select * from actor")
(c/query ds ["select id from category where name=?" "Action"])


;; Reverse relationships (can be given nicer names with shortcuts)
(c/query ds [:from :language
             :select [:name :_language.film.id]])
  
;; Names of all categories a customer has rented - two ways:
(c/queryf ds [:select :category.name
              :where [:= 2 :film.renter.id]])
(c/getf :rented-film.category.name
        (c/by-id ds :customer 2 [:select :rented-film.category.name]))
  
;; Counts of categories a customer has rented
(c/query ds [:from :category
             :select [:name :%count.id]
             :where [:= 1 :film.renter.id]
             :group-by :id])
  
;; Above query is equivalent to:
"SELECT cat.name, COUNT(cat.category_id) AS cat_count
 FROM category AS cat
 LEFT JOIN film_category AS fc ON cat.category_id = fc.category_id
 LEFT JOIN film AS f ON fc.film_id = f.film_id
 LEFT JOIN inventory AS i ON f.film_id = i.film_id
 LEFT JOIN rental AS r ON i.inventory_id = r.inventory_id
 LEFT JOIN customer AS cust ON r.customer_id = cust.customer_id
 WHERE cust.customer_id = 1
 GROUP BY cat.category_id"
  
;; Sales by store
(c/query
  ds
  {:from :store
   :select [:id :city-name :country-name
            :manager.first-name :manager.last-name
            :%sum.payment.amount]
   :group-by [:id :city-name :country-name
              :manager.id :manager.first-name :manager.last-name]
   :order-by [:country-name :city-name]})

;; Sales by category
(c/query ds [:from :category
             :select [:name :%sum.film.rental.payment.amount]
             :group-by :id])
  
;; Lazy - flat, single DB query
(c/with-query-maps maps ds [:from :film :select [:title :rating :language.name]]
  (mapv (fn [m]
          (assoc m :mature? (contains? #{"R" "NC-17"} (:rating m))))
        maps))
  
;; Vanilla insert, update
(c/insert! ds :language [{:name "Esperanto"}
                         {:name "Klingon"}])
(c/update! ds :language {:name "tlhIngan Hol"}
           [:= "Klingon" :name])
  
;; Add a new film - returns the generated ID
(c/save!
  ds :film {:title "Lawrence of Arabia"
            :language-id 1
            :category [{:id 7}           ;Drama
                       {:id 4}           ;Classics
                       {:name "Epic"}]}) ;Doesn't exist - will be created
  
;; Update a film - affects only the fields provided
(c/save! ds :film {:id 1001 :release-year 1962})
  
;; Delete a film
(c/delete! ds :film [:= 1 :id])       ;fails foreign key constraint
(c/cascading-delete-ids! ds :film 1)  ;deletes all dependent records first
  
;; Prints all SQL queries and rolls back any changes
(c/with-debug ds
  (c/cascading-delete-ids! ds :film 2))
```

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