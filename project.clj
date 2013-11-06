(defproject cantata-sample "0.1.0-SNAPSHOT"
  :description "Sample project for Cantata"
  :url "https://github.com/jkk/cantata-sample"
  :license {:name "New BSD license"
            :url "http://www.opensource.org/licenses/bsd-license.php"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [cantata "0.1.8"]
                 [mysql/mysql-connector-java "5.1.26"]
                 [org.postgresql/postgresql "9.2-1003-jdbc4"]
                 [com.h2database/h2 "1.3.173"]
                 [org.clojure/tools.logging "0.2.6"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 [org.slf4j/slf4j-log4j12 "1.7.5"]])
