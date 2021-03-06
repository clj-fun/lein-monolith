(ns lein-monolith.dependency
  "Functions for working with dependency coordinates and graphs."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [puget.color.ansi :as ansi]
    [puget.printer :as puget]))


;; ## Coordinate Functions

(defn condense-name
  "Simplifies a dependency name symbol with identical name and namespace
  components to a symbol with just a name."
  [sym]
  (when sym
    (if (= (namespace sym) (name sym))
      (symbol (name sym))
      sym)))


(defn project-name
  "Extracts the (condensed) project name from a project definition map."
  [project]
  (when project
    (condense-name (symbol (:group project) (:name project)))))


(defn unscope-coord
  "Removes the `:scope` entry from a leiningen dependency coordinate vector,
  if it is present. Preserves any metadata on the coordinate."
  [coord]
  (-> coord
      (->> (partition-all 2)
           (mapcat #(when-not (= :scope (first %)) %)))
      (vec)
      (with-meta (meta coord))))


(defn with-source
  "Attaches metadata to a dependency vector which notes the source project."
  [dependency project-name]
  (vary-meta dependency assoc :monolith/project project-name))


(defn dep-source
  "Retrieves the project which pulled in the dependency from metadata on the
  spec vector."
  [dependency]
  (:monolith/project (meta dependency)))



;; ## Dependency Graphs

(defn dependency-map
  "Converts a map of project names to definitions into a map of project names
  to sets of project-names that node depends on."
  [projects]
  (->>
    (vals projects)
    (map #(set (map (comp condense-name first)
                    (:dependencies %))))
    (zipmap (keys projects))))


(defn topological-sort
  "Returns a sequence of the keys in the map `m`, ordered such that no key `k1`
  appearing before `k2` satisfies `(contains? (get m k1) k2)`. In other words,
  earlier keys do not 'depend on' later keys."
  [m]
  (when (seq m)
    ; Note that 'roots' here are keys which no other keys depend on, hence
    ; should appear *later* in the sequence.
    (let [roots (apply set/difference (set (keys m)) (map set (vals m)))]
      (when (empty? roots)
        (throw (ex-info "Cannot sort the keys in the given map, cycle detected!"
                        {:input m})))
      (concat (topological-sort (apply dissoc m roots))
              (sort roots)))))


(defn subtree-from
  "Takes a map of node keys to sets of dependent nodes and a root node to start
  from. Returns the same dependency map containing only keys in the transitive
  subtree of the root."
  [m root]
  (loop [result {}
         front [root]]
    (if-let [node (first front)]
      (if (contains? m node)
        ; Node is part of the internal tree.
        (let [deps (set (get m node))
              new-front (set/difference deps (set (keys result)))]
          (recur
            ; Add the node to the result map.
            (assoc result node deps)
            ; Add any unprocessed dependencies to the front.
            (concat (next front) (set/difference deps (set (keys result))))))
        ; Node is not internal, so ignore.
        (recur result (next front)))
      ; No more nodes to process.
      result)))



;; ## Dependency Resolution

(defn sourced-dependencies
  "Given a project map, returns a sequence of dependency coordinates with
  metadata tracking the source."
  [project]
  (let [pn (project-name project)]
    (map #(with-source % pn) (:dependencies project))))


(defn select-dependency
  "Given a dependency name and a collection of specs for that dependency, either
  select one for use or return nil on conflicts."
  [dep-name specs]
  (let [specs (map unscope-coord specs)
        default-choice (first specs)
        projects-for-specs (reduce (fn [m d]
                                     (update m d (fnil conj []) (dep-source d)))
                                   {} specs)]
    (if (= 1 (count (distinct specs)))
      ; Only one (unique) dependency spec declared, use it.
      default-choice
      ; Multiple versions or specs declared! Warn and use the default.
      (do
        (-> (str "WARN: Multiple dependency specs found for "
                 (condense-name dep-name) " in "
                 (count (distinct (map dep-source specs)))
                 " projects - using " (pr-str default-choice) " from "
                 (dep-source default-choice))
            (ansi/sgr :red)
            (lein/warn))
        (doseq [[spec projects] projects-for-specs]
          (lein/warn (format "%-50s from %s"
                             (puget/cprint-str spec)
                             (str/join " " (sort projects)))))
        (lein/warn "")
        default-choice))))


(defn dedupe-dependencies
  "Given a vector of dependency coordinates, deduplicate and ensure there are no
  conflicting versions found."
  [dependencies]
  (let [error-flag (atom false)
        chosen-deps
        (reduce-kv
          (fn [current dep-name specs]
            (if-let [choice (select-dependency dep-name specs)]
              (conj current choice)
              (do (reset! error-flag true)
                  current)))
          []
          (group-by first dependencies))]
    (when @error-flag
      (lein/abort "Unresolvable dependency conflicts!"))
    chosen-deps))
