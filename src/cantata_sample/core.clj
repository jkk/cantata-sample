(ns cantata-sample.core
  (:require [cantata.core :as c]
            [cantata.data-source :as cds]
            [cantata.io :as cio]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.util.zip.GZIPInputStream))

(def h2-spec
  {:classname "org.h2.Driver"
   :subprotocol "h2"
   :subname "mem:film_store;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE"})

(def mysql-spec
  {:subprotocol "mysql"
   :subname "//127.0.0.1:3306/film_store"
   :user "film_store"
   :password "film_store"})

(defn init-db!
  "Sets up our database with a schema (no data yet)"
  [ds]
  (when (empty? (c/query ds "show tables"))
    (let [filename (str "create_" (cds/get-subprotocol ds) ".sql")
          sql (slurp (io/resource filename))]
      (doseq [stmt (remove string/blank? (string/split sql #";"))]
        (c/execute! ds stmt)))))

(defn import-data! [ds]
  (cio/import-data! ds (-> (io/resource "data.edn.gz")
                         (io/input-stream)
                         (GZIPInputStream.)
                         (io/reader))))

;; Supplements reflected model
(def model
  {:film {:shortcuts {:actor :film-actor.actor
                      :category :film-category.category
                      :rental :inventory.rental
                      :renter :rental.customer
                      :store :inventory.store}
          ;:validate (constantly (c/problem "boo!"))
          :hooks {} #_{:before-insert (fn [& _] (prn "before insert"))
                  :after-insert (fn [& _] (prn "after insert"))
                  :before-update (fn [& _] (prn "before update"))
                  :after-update (fn [& _] (prn "after update"))
                  :before-delete (fn [& _] (prn "before delete"))
                  :after-delete (fn [& _] (prn "after delete"))
                  :before-save (fn [& _] (prn "before save"))
                  :after-save (fn [& _] (prn "after save"))}}
   :actor {:shortcuts {:film :film-actor.film}}
   :category {:shortcuts {:film :film-category.film}}
   :customer {:shortcuts {:rented-film :rental.inventory.film
                          :city :address.city
                          :country :city.country}}
   :store {:shortcuts {:rental :inventory.rental
                       :payment :rental.payment
                       :manager :store-manager.manager
                       :city :address.city
                       :country :city.country
                       :city-name :city.city
                       :country-name :country.country}}})

(def ds
  (delay
    (c/data-source
      h2-spec model
      :init-fn init-db!
      :reflect true
      :pooled true
      :clob-str true
      :blob-bytes true
      :joda-dates true
      :unordered-maps true)))

(comment
  
  (import-data! ds)
  
  (c/query ds {:select :language.*})
  
  (c/queryf ds [:select :film.actor :where [:= 1 :id]])
  
  (c/query ds {:select [:title :release-year] :from :film})
  
  (c/query ds {:from :film :limit 1})
  
  (c/query ds "select * from actor")
  
  (c/query ds [:from :language
               :select [:name :_language.film.id]])
  
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
        :where [:= "Canada" :renter.country.country]])
  
  ;; Prepared queries, with bindable parameters
  (let [pq (c/prepare-query
             ds [:from :film
                 :select [:title :language.name]
                 :where [:= :?country :renter.country.country]])]
    (c/query ds pq :params {:country "Canada"}))
  
  ;; ...equivalent to:
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
  
  ;; above query is equivalent to:
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
     :group-by :id
     :order-by [:country-name :city-name]})
  
  ;; Sales by category
  (c/query ds [:from :category
               :select [:name :%sum.film.rental.payment.amount]
               :group-by :id])
  
  (c/query ds ["SELECT id FROM category WHERE name=?" "Action"])
  
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
  
  )



(comment
  
  ;; For subquery and explicit query testing
  
  (def dm
    ;; TODO: test without fields
    (c/data-model
      {:user {:fields [:id :username]}
       :addy {:fields [:id :user-id :street :city :state :zip]}}))
  
  ;; NYC addresses with two occupants
  (def two-occupant-ny
    {:from :addy
     :where [:= "New York" :city]
     :group-by [:street :city :zip]
     :having [:= 2 :%count.user-id]})
  
  ;; Users different from each other
  (def userq
    {:from :user
     :join [[:user :u2] [:< :id :u2.id]]})

  ;; Put it all together
  (def finalq
    (c/build-query
      userq
      {:select [:* :u2.* :a1.* :a2.*]
       :join [[:addy :a3] [:= :id :a3.user-id]
              [:addy :a4] [:= :u2.id :a4.user-id]
              [two-occupant-ny :occ2] [:and
                                       [:= :occ2.street :a3.street]
                                       [:= :occ2.city :a3.city]
                                       [:= :occ2.state :a3.state]
                                       [:= :occ2.zip :a3.zip]
                                       [:= :occ2.street :a4.street]
                                       [:= :occ2.city :a4.city]
                                       [:= :occ2.state :a4.state]
                                       [:= :occ2.zip :a4.zip]]]
       :left-join [[:addy :a1] [:= :id :a1.user-id]
                   [:addy :a2] [:= :u2.id :a2.user-id]]
       :where [:not [:exists {:from :addy
                              :select :id
                              :where [:and
                                      [:not= "New York" :city]
                                      [:in :user-id [:user.id :u2.id]]]}]]}))
  
  ;; cantata
  ["SELECT \"user\".\"id\" AS \"id\", \"user\".\"username\" AS \"username\", \"u2\".\"id\" AS \"u2.id\",
      \"u2\".\"username\" AS \"u2.username\", \"a1\".\"id\" AS \"a1.id\",
      \"a1\".\"user_id\" AS \"a1.user_id\", \"a1\".\"street\" AS \"a1.street\",
      \"a1\".\"city\" AS \"a1.city\", \"a1\".\"state\" AS \"a1.state\", \"a1\".\"zip\" AS \"a1.zip\",
      \"a2\".\"id\" AS \"a2.id\", \"a2\".\"user_id\" AS \"a2.user_id\",
      \"a2\".\"street\" AS \"a2.street\", \"a2\".\"city\" AS \"a2.city\",
      \"a2\".\"state\" AS \"a2.state\", \"a2\".\"zip\" AS \"a2.zip\"
   FROM \"user\" AS \"user\"
   INNER JOIN \"user\" AS \"u2\" ON \"user\".\"id\" < \"u2\".\"id\"
   INNER JOIN \"addy\" AS \"a3\" ON \"user\".\"id\" = \"a3\".\"user_id\"
   INNER JOIN \"addy\" AS \"a4\" ON \"u2\".\"id\" = \"a4\".\"user_id\"
   INNER JOIN (SELECT \"user\".\"id\" AS \"id\", \"user_id\" AS \"user_id\",
                 \"street\" AS \"street\", \"city\" AS \"city\",
                 \"state\" AS \"state\", \"zip\" AS \"zip\"
               FROM \"addy\" AS \"addy\"
               WHERE ? = \"city\"
               GROUP BY \"street\", \"city\", \"zip\"
               HAVING 2 = count(\"user_id\")) AS \"occ2\"
              ON (\"occ2\".\"street\" = \"a3\".\"street\" AND \"occ2\".\"city\" = \"a3\".\"city\"
                  AND \"occ2\".\"state\" = \"a3\".\"state\" AND \"occ2\".\"zip\" = \"a3\".\"zip\"
                  AND \"occ2\".\"street\" = \"a4\".\"street\" AND \"occ2\".\"city\" = \"a4\".\"city\"
                  AND \"occ2\".\"state\" = \"a4\".\"state\" AND \"occ2\".\"zip\" = \"a4\".\"zip\")
   LEFT JOIN \"addy\" AS \"a1\" ON \"user\".\"id\" = \"a1\".\"user_id\"
   LEFT JOIN \"addy\" AS \"a2\" ON \"u2\".\"id\" = \"a2\".\"user_id\"
   WHERE NOT exists((SELECT \"user\".\"id\" AS \"id\"
                     FROM \"addy\" AS \"addy\"
                     WHERE (? <> \"city\"
                            AND (\"user_id\" in (\"user\".\"id\", \"u2\".\"id\")))))"
   "New York" "New York"]
  
  ;; modelo
  ["SELECT user.id AS \"id\", user.username AS \"username\", u2.id AS \"u2.id\",
       u2.username AS \"u2.username\", a1.id AS \"a1.id\",
       a1.user_id AS \"a1.user-id\", a1.street AS \"a1.street\",
       a1.city AS \"a1.city\", a1.state AS \"a1.state\", a1.zip AS \"a1.zip\",
       a2.id AS \"a2.id\", a2.user_id AS \"a2.user-id\",
       a2.street AS \"a2.street\", a2.city AS \"a2.city\",
       a2.state AS \"a2.state\", a2.zip AS \"a2.zip\"
   FROM user AS user
   INNER JOIN user AS u2 ON user.id < u2.id
   INNER JOIN address AS a3 ON user.id = a3.user_id
   INNER JOIN address AS a4 ON u2.id = a4.user_id
   INNER JOIN (SELECT address.id AS id, address.user_id AS user_id,
                   address.street AS street, address.city AS city
                   address.state AS state, address.zip AS zip
               FROM address AS address
               WHERE ? = address.city
               GROUP BY address.street, address.city, address.zip
               HAVING 2 = COUNT(address.user_id)) AS occ2
              ON (occ2.street = a3.street AND occ2.city = a3.city
                  AND occ2.state = a3.state AND occ2.zip = a3.zip
                  AND occ2.street = a4.street AND occ2.city = a4.city
                  AND occ2.state = a4.state AND occ2.zip = a4.zip)
   LEFT JOIN address AS a1 ON user.id = a1.user_id
   LEFT JOIN address AS a2 ON u2.id = a2.user_id
   WHERE NOT EXISTS((SELECT address.id AS id
                     FROM address AS address
                     WHERE (? <> address.city
                            AND (address.user_id IN (user.id, u2.id)))))"
   "New York" "New York"]
  
  
  
  
  
    )