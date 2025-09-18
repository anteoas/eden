(ns eden.site-generator-test.warnings-test
  "Tests for warning generation in Eden's site generator.

   This namespace specifically tests scenarios that SHOULD generate warnings.
   Each test verifies:
   1. The warning is generated (count > 0)
   2. The warning has the correct :type
   3. The warning contains all necessary data fields for reporting
   4. The fallback/error output is rendered correctly

   DO NOT test success cases here - those belong in site-generator-test.
   DO NOT test scenarios where warnings should NOT be generated.

   Each warning type should have comprehensive coverage including:
   - Basic warning generation
   - Warning in different contexts (nested, loops, conditionals)
   - Multiple warnings of the same type
   - Data structure completeness for error reporting"
  (:require [clojure.test :refer [deftest is testing] :as t]
            [eden.site-generator :as sg]
            [malli.core :as m]
            [malli.error :as me]))

(def MissingKey
  [:map
   [:type [:= :missing-key]]
   [:template :keyword]
   [:content-key :keyword]
   [:directive :keyword]
   [:key :keyword]])

(def MissingRenderTemplate
  [:map
   [:type [:= :missing-render-template]]
   [:directive [:= :eden/render]]
   [:parent :keyword]
   [:lang :keyword]
   [:template :keyword]
   [:spec [:map
           [:data :keyword]]]
   [:content-key :keyword]])

(def MissingPageContent
  [:map
   [:type [:= :missing-page-content]]
   [:directive [:= :eden/render]]
   [:parent :keyword]
   [:lang :keyword]
   [:spec [:map [:data :keyword]]]
   [:content-key :keyword]])

(def NotAString
  [:map
   [:type [:= :not-a-string]]
   [:directive [:= :eden/t]]
   [:content-key [:maybe :keyword]]
   [:lang [:maybe :keyword]]
   [:value any?]
   [:template-variable :string]
   [:template-string :string]
   [:form [:vector {:min 3} any?]]])

(def MissingIncludeTemplate
  [:map
   [:type [:= :missing-include-template]]
   [:directive [:= :eden/include]]
   [:template :keyword]
   [:content-key [:maybe :keyword]]])

(def Warning
  [:multi {:dispatch :type}
   [:missing-key MissingKey]
   [:missing-render-template MissingRenderTemplate]
   [:missing-page-content MissingPageContent]
   [:not-a-string NotAString]
   [:missing-include-template MissingIncludeTemplate]])

(defn malli-error
  ([schema]
   (fn [value] (me/humanize (m/explain schema value))))
  ([schema value]
   (me/humanize (m/explain schema value))))

(deftest missing-key-warning
  (testing "Basic missing key in eden/get"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/get :missing-key]"]
             (sg/process [:eden/get :missing-key]
                         {:data {:existing-key "value"
                                 :template :t}
                          :content-key :home
                          :warn! #(swap! warnings conj %)})))
      (is (= 1 (count @warnings)))
      (is (nil? (malli-error MissingKey (first @warnings))))))

  (testing "Missing key with template context"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/get :missing-key]"]
             (sg/process [:eden/get :missing-key]
                         {:data {:template :my-template
                                 :existing-key "value"}
                          :content-key :my-content
                          :warn! #(swap! warnings conj %)})))
      (is (nil? (malli-error MissingKey (first @warnings))))
      (is (= {:type :missing-key
              :template :my-template
              :content-key :my-content
              :key :missing-key
              :directive :eden/get}
             (first @warnings)))))

  (testing "Missing key in nested structure"
    (let [warnings (atom [])]
      (is (= [:div
              [:h1 "Title"]
              [:p [:span.missing-content "[:eden/get :description]"]]]
             (sg/process [:div
                          [:h1 [:eden/get :title]]
                          [:p [:eden/get :description]]]
                         {:data {:title "Title" :template :t}
                          :content-key :my-content
                          :warn! #(swap! warnings conj %)})))
      (is (nil? (malli-error MissingKey (first @warnings))))
      (is (= 1 (count @warnings)))
      (is (= :missing-key (:type (first @warnings))))
      (is (= :description (:key (first @warnings))))))

  (testing "Multiple missing keys generate multiple warnings"
    (let [warnings (atom [])]
      (is (= [:div
              [:span.missing-content "[:eden/get :missing1]"]
              [:span.missing-content "[:eden/get :missing2]"]
              [:span.missing-content "[:eden/get :missing3]"]]
             (sg/process [:div
                          [:eden/get :missing1]
                          [:eden/get :missing2]
                          [:eden/get :missing3]]
                         {:data {:template :t}
                          :content-key :hoho
                          :warn! #(swap! warnings conj %)})))
      (let [schema-errors (map (malli-error MissingKey) @warnings)]
        (is (every? nil? schema-errors)))
      (is (= 3 (count @warnings)))
      (is (= #{:missing1 :missing2 :missing3}
             (set (map :key @warnings))))))

  (testing "Missing key in eden/each iteration"
    (let [warnings (atom [])]
      (is (= [[:li "Item 1" ": " [:span.missing-content "[:eden/get :description]"]]
              [:li "Item 2" ": " [:span.missing-content "[:eden/get :description]"]]]
             (sg/process [:eden/each :items
                          [:li [:eden/get :name] ": " [:eden/get :description]]]
                         {:data {:template :t
                                 :items [{:name "Item 1"}
                                         {:name "Item 2"}]}
                          :content-key :hoho
                          :warn! #(swap! warnings conj %)})))

      (is (= 2 (count @warnings))
          "Should warn for each iteration with missing key")
      (let [schema-errors (map (malli-error MissingKey) @warnings)]
        (is (every? nil? schema-errors)))))

  (testing "Missing key in eden/if condition evaluates as truthy"
    (let [warnings (atom [])]
      (is (= [:div "true branch"]
             (sg/process [:eden/if [:eden/get :missing-condition]
                          [:div "true branch"]
                          [:div "fallback"]]
                         {:data {}
                          :warn! #(swap! warnings conj %)})))
      (is (= 1 (count @warnings)))
      (is (= :missing-key (:type (first @warnings))))))

  (testing "Missing key in eden/with context"
    (let [warnings (atom [])]
      (is (= [[:div [:span.missing-content "[:eden/get :nested-missing]"]]]
             (sg/process [:eden/with :user
                          [:div [:eden/get :nested-missing]]]
                         {:data {:user {:name "John"}}
                          :warn! #(swap! warnings conj %)})))
      (is (= 1 (count @warnings)))
      (is (= :missing-key (:type (first @warnings))))
      (is (= :nested-missing (:key (first @warnings))))))

  (testing "Missing :html/content key"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/get :html/content]"]
             (sg/process [:eden/get :html/content]
                         {:data {}
                          :warn! #(swap! warnings conj %)})))
      (is (= 1 (count @warnings)))
      (is (= :html/content (:key (first @warnings))))))

  (testing "Processed/evaluated key that results in missing key"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/get :final-key]"]
             (sg/process [:eden/get [:eden/get :key-name]]
                         {:data {:key-name :final-key}
                          :warn! #(swap! warnings conj %)})))
      (is (= 1 (count @warnings)))
      (is (= :final-key (:key (first @warnings))))))

  (testing "Warning data structure completeness"
    (let [warnings (atom [])]
      (sg/process [:eden/get :missing]
                  {:data {:template :test-template
                          :other "data"}
                   :warn! #(swap! warnings conj %)})
      (let [warning (first @warnings)]
        (is (contains? warning :type))
        (is (contains? warning :template))
        (is (contains? warning :key))
        (is (contains? warning :directive))
        (is (= :missing-key (:type warning)))
        (is (= :test-template (:template warning)))
        (is (= :missing (:key warning)))
        (is (= :eden/get (:directive warning)))))))

(deftest missing-render-template-warning
  (testing "Missing render template generates warning with all required fields"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/render :sidebar]"]
             (sg/process [:eden/render :sidebar]
                         {:content {:en {:sidebar {:hello "world"
                                                   :template :my-nonexistent-template}}}
                          :warn! #(swap! warnings conj %)
                          :lang :en
                          :content-key :home
                          :templates {:sidebar [:h2 [:eden/get :hello]]}})))

      (is (= 1 (count @warnings)))
      (let [warning (first @warnings)]
        (is (m/validate MissingRenderTemplate warning))
        (is (= {:type :missing-render-template
                :directive :eden/render
                :content-key :sidebar
                :parent :home
                :lang :en
                :template :my-nonexistent-template
                :spec {:data :sidebar}}
               warning))))))

(deftest missing-page-content-warning
  (testing "Missing page content generates warning with all required fields"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/render :this-page-is-not-found]"]
             (sg/process [:eden/render :this-page-is-not-found]
                         {:content {:en {:sidebar {:hello "world"}}}
                          :warn! #(swap! warnings conj %)
                          :lang :en
                          :content-key :home
                          :templates {:sidebar [:h2 [:eden/get :hello]]}})))
      (is (= 1 (count @warnings)))
      (let [warning (first @warnings)]
        (is (m/validate MissingPageContent warning))
        (is (= {:type :missing-page-content
                :directive :eden/render
                :lang :en
                :spec {:data :this-page-is-not-found}
                :content-key :this-page-is-not-found
                :parent :home}
               warning))))))

(deftest not-a-string-warning
  (testing "Non-string interpolation in eden/t generates warning"
    (let [warnings (atom [])]
      (sg/process [:eden/t :welcome {:user 42}]
                  {:strings {:welcome "Welcome, {{user}}"}
                   :content-key :dashboard
                   :lang :en
                   :warn! #(swap! warnings conj %)})
      (is (= 1 (count @warnings)))
      (let [warning (first @warnings)]
        (is (m/validate NotAString warning))
        (is (= :not-a-string (:type warning)))
        (is (= :eden/t (:directive warning)))
        (is (= :dashboard (:content-key warning)))
        (is (= :en (:lang warning)))
        (is (= 42 (:value warning)))
        (is (= "{{user}}" (:template-variable warning)))
        (is (= "Welcome, {{user}}" (:template-string warning)))
        (is (= [:eden/t :welcome {:user 42}] (:form warning))))))

  (testing "Nil value in interpolation generates warning"
    (let [warnings (atom [])]
      (sg/process [:eden/t :greeting {:name nil}]
                  {:strings {:greeting "Hello, {{name}}!"}
                   :content-key :contact
                   :lang :en
                   :warn! #(swap! warnings conj %)})
      (is (= 1 (count @warnings)))
      (let [warning (first @warnings)]
        (is (m/validate NotAString warning))
        (is (= nil (:value warning)))
        (is (= :contact (:content-key warning)))
        (is (= :en (:lang warning)))))))

(deftest missing-include-template-warning
  (testing "Missing include template generates warning"
    (let [warnings (atom [])]
      (is (= nil
             (sg/process [:eden/include :missing-partial {:title "Override"}]
                         {:templates {:other [:div "exists"]}
                          :content-key :home
                          :data {:title "Original"}
                          :warn! #(swap! warnings conj %)})))
      (is (= 1 (count @warnings)))
      (let [warning (first @warnings)]
        (is (m/validate MissingIncludeTemplate warning))
        (is (= {:type :missing-include-template
                :directive :eden/include
                :template :missing-partial
                :content-key :home}
               warning))))))

#_(t/run-tests)
