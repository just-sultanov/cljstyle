(ns cljfmt.core
  #?@(:clj
      [(:require
         [cljfmt.fn :as fn]
         [cljfmt.indent :as indent]
         [cljfmt.ns :as ns]
         [cljfmt.zloc :as zl]
         [clojure.java.io :as io]
         [clojure.zip :as zip]
         [rewrite-clj.node :as n]
         [rewrite-clj.parser :as p]
         [rewrite-clj.zip :as z
          :refer [append-space edn skip]])]
      :cljs
      [(:require
         [cljfmt.fn :as fn]
         [cljfmt.indent :as indent]
         [cljfmt.ns :as ns]
         [cljfmt.zloc :as zl]
         [clojure.zip :as zip]
         [rewrite-clj.node :as n]
         [rewrite-clj.parser :as p]
         [rewrite-clj.zip :as z]
         [rewrite-clj.zip.base :as zb :refer [edn]]
         [rewrite-clj.zip.whitespace :as zw
          :refer [append-space skip]])
       (:require-macros
         [cljfmt.core :refer [read-resource]])]))


#?(:clj (def read-resource* (comp read-string slurp io/resource)))
#?(:clj (defmacro read-resource [path] `'~(read-resource* path)))


(def default-indents
  (merge (read-resource "cljfmt/indents/clojure.clj")
         (read-resource "cljfmt/indents/compojure.clj")
         (read-resource "cljfmt/indents/fuzzy.clj")))



;; ## Editing Functions

(defn- edit-all
  "Edit all nodes in `zloc` matching the predicate by applying `f` to them.
  Returns the final zipper location."
  [zloc p? f]
  (loop [zloc (if (p? zloc) (f zloc) zloc)]
    (if-let [zloc (z/find-next zloc zip/next p?)]
      (recur (f zloc))
      zloc)))


(defn- transform
  "Transform this form by parsing it as an EDN syntax tree and applying `zf` to
  it."
  [form zf & args]
  (z/root (apply zf (edn form) args)))


(defn- whitespace
  "Build a new whitespace node with `width` spaces."
  [width]
  (n/whitespace-node (apply str (repeat width " "))))



;; ## Rule: Function Line Breaks

(defn- eat-whitespace
  [zloc]
  (loop [zloc zloc]
    (if (zl/zwhitespace? zloc)
      (recur (zip/next (zip/remove zloc)))
      zloc)))


(defn- replace-whitespace
  [form p? f]
  (transform
    form edit-all p?
    (fn [zloc]
      (if (f zloc)
        ; break space
        (if (zl/zlinebreak? zloc)
          (z/right zloc)
          (-> zloc
              (zip/replace (n/newlines 1))
              (zip/right)
              (eat-whitespace)))
        ; inline space
        (-> zloc
            (zip/replace (whitespace 1))
            (zip/right)
            (eat-whitespace))))))


(defn line-break-functions
  "Transform this form by applying line-breaks to `defn` and `fn` forms."
  [form]
  ; TODO: do more in fn ns
  (-> form
      (replace-whitespace
        fn/fn-to-name-or-args-space?
        (constantly false))
      (replace-whitespace
        fn/post-name-space?
        fn/defn-or-multiline?)
      (replace-whitespace
        fn/post-doc-space?
        (constantly true))
      (replace-whitespace
        fn/post-args-space?
        fn/defn-or-multiline?)))



;; ## Rule: Consecutive Blank Lines

; TODO: insert a configurable number of blank lines around top-level forms which span multiple lines
; TODO: config to allow max number of consecutive blank lines

(defn- count-newlines
  "Count the number of consecutive blank lines at this location."
  [zloc]
  (loop [zloc zloc, newlines 0]
    (if (zl/zlinebreak? zloc)
      (recur (-> zloc zip/right zl/skip-whitespace)
             (-> zloc z/string count (+ newlines)))
      newlines)))


(defn- consecutive-blank-line?
  "True if more than one blank line follows this location."
  [zloc]
  (> (count-newlines zloc) 2))


(defn- remove-whitespace-and-newlines
  "Edit the node at this location to remove any following whitespace."
  [zloc]
  (if (zl/zwhitespace? zloc)
    (recur (zip/remove zloc))
    zloc))


(defn- replace-consecutive-blank-lines
  "Replace the node at this location with one blank line and remove any
  following whitespace and linebreaks."
  [zloc]
  ; TODO: config to allow 1-n blank lines based on context?
  (-> zloc (zip/replace (n/newlines 2)) zip/next remove-whitespace-and-newlines))


(defn remove-consecutive-blank-lines
  "Edit the form to replace consecutive blank lines with a single line."
  [form]
  (transform form edit-all consecutive-blank-line? replace-consecutive-blank-lines))



;; ## Rule: Surrounding Whitespace

(defn- surrounding?
  "True if the predicate applies to `zloc` and it is either the left-most node
  or all nodes to the right also match the predicate."
  [zloc p?]
  (and (p? zloc) (or (nil? (zip/left zloc))
                     (nil? (skip zip/right p? zloc)))))


(defn- surrounding-whitespace?
  "True if the node at this location is part of whitespace surrounding a
  top-level form."
  [zloc]
  (and (zl/top? (z/up zloc))
       (surrounding? zloc zl/zwhitespace?)))


(defn remove-surrounding-whitespace
  "Transform this form by removing any surrounding whitespace nodes."
  [form]
  (transform form edit-all surrounding-whitespace? zip/remove))



;; ## Rule: Missing Whitespace

(defn- missing-whitespace?
  "True if the node at this location is an element and the immediately
  following location is a different element."
  [zloc]
  (and (zl/element? zloc)
       (not (zl/reader-macro? (zip/up zloc)))
       (zl/element? (zip/right zloc))))


(defn insert-missing-whitespace
  "Insert a space between abutting elements in the form."
  [form]
  (transform form edit-all missing-whitespace? append-space))



;; ## Rule: Indentation

(defn- unindent
  "Remove indentation whitespace from the form in preparation for reformatting."
  [form]
  (transform form edit-all indent/should-unindent? zip/remove))


(defn- indent-line
  "Apply indentation to the line beginning at this location."
  [zloc indents]
  (let [width (indent/indent-amount zloc indents)]
    (if (pos? width)
      (zip/insert-right zloc (whitespace width))
      zloc)))


(defn indent
  "Transform this form by indenting all lines their proper amounts."
  ([form]
   (indent form default-indents))
  ([form indents]
   (transform form edit-all indent/should-indent? #(indent-line % indents))))


(defn reindent
  "Transform this form by rewriting all line indentation."
  ([form]
   (indent (unindent form)))
  ([form indents]
   (indent (unindent form) indents)))



;; ## Rule: Trailing Whitespace

(defn- final?
  "True if this location is the last top-level node."
  [zloc]
  (and (nil? (zip/right zloc)) (zl/root? (zip/up zloc))))


(defn- trailing-whitespace?
  "True if the node at this location represents whitespace trailing a form on a
  line or the final top-level node."
  [zloc]
  (and (zl/whitespace? zloc)
       (or (zl/zlinebreak? (zip/right zloc)) (final? zloc))))


(defn remove-trailing-whitespace
  "Transform this form by removing all trailing whitespace."
  [form]
  (transform form edit-all trailing-whitespace? zip/remove))



;; ## Rule: Namespace Rewriting

(defn rewrite-namespaces
  "Transform this form by rewriting any namespace forms."
  [form opts]
  (transform form edit-all ns/ns-node? #(ns/rewrite-ns-form % opts)))



;; ## Reformatting Functions

(defn reformat-form
  "Transform this form by applying formatting rules to it."
  [form & [{:as opts}]]
  (cond-> form
    (:remove-surrounding-whitespace? opts true)
      (remove-surrounding-whitespace)
    (:insert-missing-whitespace? opts true)
      (insert-missing-whitespace)
    (:line-break-functions? opts true)
      (line-break-functions)
    ; TODO: line-break-types
    (:remove-consecutive-blank-lines? opts true)
      (remove-consecutive-blank-lines)
    ; TODO: insert-top-padding-lines
    (:indentation? opts true)
      (reindent (:indents opts default-indents))
    (:rewrite-namespaces? opts true)
      (rewrite-namespaces opts)
    (:remove-trailing-whitespace? opts true)
      (remove-trailing-whitespace)))


(defn reformat-string
  "Helper method to transform a string by parsing it, formatting it, then
  printing it."
  [form-string & [options]]
  (-> (p/parse-string-all form-string)
      (reformat-form options)
      (n/string)))
