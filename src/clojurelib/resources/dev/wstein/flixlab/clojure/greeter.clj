(ns dev.wstein.flixlab.clojure.greeter)

(defn- subject []
  "Clojure")

(defn greeting []
  (let [name (subject)]
    (str "Hello " name "!")))
