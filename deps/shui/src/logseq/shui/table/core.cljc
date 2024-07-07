(ns logseq.shui.table.core
  "Table"
  (:require [logseq.shui.table.impl :as impl]
            [dommy.core :refer-macros [sel1]]
            [rum.core :as rum]))

(defn- get-head-container
  []
  (sel1 "#head"))

(defn- get-main-scroll-container
  []
  (sel1 "#main-content-container"))

(defn- row-selected?
  [row row-selection]
  (let [id (:id row)]
    (or
     (and (:selected-all? row-selection)
          ;; exclude ids
          (not (contains? (:excluded-ids row-selection) id)))
     (and (not (:selected-all? row-selection))
          ;; included ids
          (contains? (:selected-ids row-selection) id)))))

(defn- select-some?
  [row-selection rows]
  (boolean
   (or
    (seq (:selected-ids row-selection))
    (and (seq (:exclude-ids row-selection))
         (not= (count rows) (count (:exclude-ids row-selection)))))))

(defn- toggle-selected-all!
  [value set-row-selection!]
  (if value
    (set-row-selection! {:selected-all? value})
    (set-row-selection! {})))

(defn- set-conj
  [col item]
  (if (seq col)
    (conj (if (set? col) col (set col)) item)
    (conj #{} item)))

(defn- row-toggle-selected!
  [row value set-row-selection! row-selection]
  (let [id (:id row)
        new-selection (if (:selected-all? row-selection)
                        (update row-selection :excluded-ids (if value disj set-conj) id)
                        (update row-selection :selected-ids (if value set-conj disj) id))]
    (set-row-selection! new-selection)))

(defn- column-toggle-sorting!
  [column set-sorting! sorting {:keys [sort-by-one-column?]
                                :or {sort-by-one-column? true}}]
  (let [id (:id column)
        existing-column (some (fn [item] (when (= (:id item) id) item)) sorting)
        value (if existing-column
                (mapv (fn [item] (when (= (:id item) id) (update item :asc? not))) sorting)
                (conj (if (vector? sorting) sorting (vec sorting)) {:id id :asc? true}))
        value' (if sort-by-one-column?
                 (filterv (fn [item] (when (= (:id item) id) item)) value)
                 value)]
    (set-sorting! value')))

(defn get-selection-rows
  [row-selection rows]
  (if (:selected-all? row-selection)
    (let [excluded-ids (:excluded-ids row-selection)]
      (if (seq excluded-ids)
        (remove #(excluded-ids (:id %)) rows)
        rows))
    (let [selected-ids (:selected-ids row-selection)]
      (when (seq selected-ids)
        (filter #(selected-ids (:id %)) rows)))))

(defn table-option
  [{:keys [data columns state data-fns]
    :as option}]
  (let [{:keys [sorting row-filter row-selection visible-columns]} state
        {:keys [set-sorting! set-visible-columns! set-row-selection!]} data-fns
        columns' (impl/visible-columns columns visible-columns)
        filtered-rows (impl/rows {:rows data
                                  :columns columns
                                  :sorting sorting
                                  :row-filter row-filter})]
    (assoc option
           ;; visible columns
           :columns columns'
           ;; filtered rows
           :rows filtered-rows

           ;; fns
           :column-visible? (fn [column] (impl/column-visible? column visible-columns))
           :column-toggle-visibility (fn [column v] (set-visible-columns! (assoc visible-columns (impl/column-id column) v)))
           :selected-all? (:selected-all? row-selection)
           :selected-some? (select-some? row-selection filtered-rows)
           :row-selected? (fn [row] (row-selected? row row-selection))
           :row-toggle-selected! (fn [row value] (row-toggle-selected! row value set-row-selection! row-selection))
           :toggle-selected-all! (fn [value] (toggle-selected-all! value set-row-selection!))
           :column-toggle-sorting! (fn [column & {:as option}] (column-toggle-sorting! column set-sorting! sorting option)))))

(defn- get-prop-and-children
  [prop-and-children]
  (let [prop (when (map? (first prop-and-children)) (first prop-and-children))]
    (if prop
      [prop (rest prop-and-children)]
      [{} prop-and-children])))

(rum/defc table < rum/static
  [& prop-and-children]
  (let [[prop children] (get-prop-and-children prop-and-children)]
    [:div (merge {:class "ls-table w-full caption-bottom text-sm table-fixed"}
                   prop)
     children]))

;; FIXME: sticky header
(rum/defc table-header < rum/static
  [& prop-and-children]
  (let [[prop children] (get-prop-and-children prop-and-children)
        el-ref (rum/use-ref nil)]

    (rum/use-effect!
      (fn []
        (let [^js container (get-main-scroll-container)
              ^js el (rum/deref el-ref)
              ^js cls (.-classList el)
              *ticking? (volatile! false)
              el-top (-> el (.getBoundingClientRect) (.-top))
              head-top (-> (get-head-container) (js/getComputedStyle) (.-height) (js/parseInt))
              translate (fn [offset]
                          (set! (. (.-style el) -transform) (str "translate3d(0, " offset "px , 0)"))
                          (if (zero? offset)
                            (.remove cls "translated")
                            (.add cls "translated")))
              *last-offset (volatile! 0)
              handle (fn []
                       (let [scroll-top (js/parseInt (.-scrollTop container))
                             offset (if (> (+ scroll-top head-top) el-top)
                                      (+ (- scroll-top el-top) head-top 1) 0)
                             offset (js/parseInt offset)
                             last-offset @*last-offset]
                         (if (and (not (zero? last-offset))
                               (not= offset last-offset))
                           (let [dir (if (neg? (- offset last-offset)) -1 1)]
                             (loop [offset' (+ last-offset dir)]
                               (translate offset')
                               (if (and (not= offset offset')
                                       (< (abs (- offset offset')) 100))
                                 (recur (+ offset' dir))
                                 (translate offset))))
                           (translate offset))
                         (vreset! *last-offset offset)))
              handler (fn [^js e]
                        (when (not @*ticking?)
                          (js/window.requestAnimationFrame
                            #(do (handle) (vreset! *ticking? false)))
                          (vreset! *ticking? true)))]
          (.addEventListener container "scroll" handler)
          #(.removeEventListener container "scroll" handler)))
      [])

    [:div.ls-table-header
     (merge {:class "border-y transition-colors bg-gray-01"
             :ref el-ref
             :style {:z-index 100}}
            prop)
     children]))

(rum/defc table-row < rum/static
  [& prop-and-children]
  (let [[prop children] (get-prop-and-children prop-and-children)]
    [:div.ls-table-row.flex.flex-row.items-center (merge {:class "border-b transition-colors hover:bg-muted/50 data-[state=selected]:bg-muted bg-gray-01 items-stretch"}
                               prop)
     children]))

(rum/defc table-head < rum/static
  [& prop-and-children]
  (let [[prop children] (get-prop-and-children prop-and-children)]
    [:div (merge {:class "cursor-pointer transition-colors hover:bg-muted/50 px-2 text-left align-middle font-medium text-muted-foreground [&:has([role=checkbox])]:px-0"}
                prop)
     children]))

(rum/defc table-cell < rum/static
  [& prop-and-children]
  (let [[prop children] (get-prop-and-children prop-and-children)]
    [:div.flex.relative prop
     [:div {:class (str "flex align-middle w-full overflow-x-clip items-center"
                        (cond
                          (:select? prop)
                          " px-0"
                          (:last-column? prop)
                          " px-2"
                          :else
                          " border-r px-2"))}
      children]]))
