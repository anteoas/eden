(ns eden.site-generator-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eden.site-generator :as sg]))

(deftest test-body-replacement
  (testing "Basic :eden/body replacement"
    (let [base [:div [:eden/body]]
          content [:p "Hi"]
          expected [:div [:p "Hi"]]]
      (is (= expected (sg/process base content)))))

  (testing "Nested :eden/body"
    (let [base [:div [:main [:eden/body]]]
          content [:p "Hi"]
          expected [:div [:main [:p "Hi"]]]]
      (is (= expected (sg/process base content)))))

  (testing "No :eden/body"
    (let [base [:div "Hi"]
          content [:p "Ignored"]]
      (is (= base (sg/process base content)))))

  (testing "Vector of vectors splices elements"
    (let [base [:main [:eden/body]]
          content [[:h1 "Title"]
                   [:p "Paragraph"]]
          expected [:main [:h1 "Title"] [:p "Paragraph"]]]
      (is (= expected (sg/process base content)))))

  (testing "Vector of vectors with multiple children"
    (let [base [:div
                [:header "Header"]
                [:eden/body]
                [:footer "Footer"]]
          content [[:section "One"]
                   [:section "Two"]]
          expected [:div
                    [:header "Header"]
                    [:section "One"]
                    [:section "Two"]
                    [:footer "Footer"]]]
      (is (= expected (sg/process base content))))))

(deftest test-include
  (testing "Basic include"
    (let [base [:div [:eden/include :footer]]
          includes {:footer [:footer "Footer content"]}
          expected [:div [:footer "Footer content"]]]
      (is (= expected (sg/process base {:templates includes})))))

  (testing "Include with body"
    (let [base [:div
                [:eden/body]
                [:eden/include :footer]]
          content {:body [:p "Content"]
                   :templates {:footer [:footer "Footer"]}}
          expected [:div
                    [:p "Content"]
                    [:footer "Footer"]]]
      (is (= expected (sg/process base content)))))

  (testing "Missing include returns placeholder"
    (let [base [:div [:eden/include :missing]]
          includes {}
          expected [:div [:eden/include :missing]]]
      (is (= expected (sg/process base {:templates includes}))))))

(deftest test-extract-image-urls
  (testing "Extract image URLs with query parameters from HTML"
    (let [html "<div>
                  <img src=\"/assets/images/hero.jpg?size=800x600&format=webp\" alt=\"Hero\">
                  <img src=\"/assets/images/logo.png?size=200x100\" alt=\"Logo\">
                  <div style=\"background-image: url('/assets/images/bg.jpg?size=1920x1080&format=webp')\"></div>
                </div>"
          expected [{:url "/assets/images/hero.jpg?size=800x600&format=webp"
                     :source-path "/assets/images/hero.jpg"
                     :width 800 :height 600
                     :replace-url "/assets/images/hero-800x600.jpg"}
                    {:url "/assets/images/logo.png?size=200x100"
                     :source-path "/assets/images/logo.png"
                     :width 200 :height 100
                     :replace-url "/assets/images/logo-200x100.png"}
                    {:url "/assets/images/bg.jpg?size=1920x1080&format=webp"
                     :source-path "/assets/images/bg.jpg"
                     :width 1920 :height 1080
                     :replace-url "/assets/images/bg-1920x1080.jpg"}]]
      (is (= expected (sg/extract-image-urls html)))))

  (testing "Extract images without query parameters"
    (let [html "<img src=\"/assets/images/simple.jpg\" alt=\"Simple\">"
          expected []]
      (is (= expected (sg/extract-image-urls html)))))

  (testing "Skip external URLs"
    (let [html "<img src=\"https://example.com/image.jpg?size=100x100\" alt=\"External\">"
          expected []]
      (is (= expected (sg/extract-image-urls html)))))

  (testing "Handle images in various contexts"
    (let [html "<html>
                  <img src='/assets/images/photo.png?size=400x300&quality=85'>
                  <div style='background: linear-gradient(rgba(0,0,0,0.5), rgba(0,0,0,0.5)), url(\"/assets/images/hero.jpg?size=1200x800\")'>
                  <img src=\"/assets/images/icon.svg?size=32x32\"/>
                </html>"
          expected [{:url "/assets/images/photo.png?size=400x300&quality=85"
                     :source-path "/assets/images/photo.png"
                     :width 400 :height 300
                     :replace-url "/assets/images/photo-400x300.png"}
                    {:url "/assets/images/hero.jpg?size=1200x800"
                     :source-path "/assets/images/hero.jpg"
                     :width 1200 :height 800
                     :replace-url "/assets/images/hero-1200x800.jpg"}
                    {:url "/assets/images/icon.svg?size=32x32"
                     :source-path "/assets/images/icon.svg"
                     :width 32 :height 32
                     :replace-url "/assets/images/icon-32x32.svg"}]]
      (is (= expected (sg/extract-image-urls html)))))

  (testing "Handle width-only sizing"
    (let [html "<img src='/assets/images/wide.jpg?size=800x&format=webp'>"
          expected [{:url "/assets/images/wide.jpg?size=800x&format=webp"
                     :source-path "/assets/images/wide.jpg"
                     :width 800
                     :replace-url "/assets/images/wide-800x.jpg"}]]
      (is (= expected (sg/extract-image-urls html)))))

  (testing "Handle malformed parameters gracefully"
    (let [html "<div>
                  <img src='/assets/images/bad1.jpg?size=notanumber'>
                  <img src='/assets/images/bad2.jpg?size=800xabc'>
                  <img src='/assets/images/bad3.jpg?quality=high'>
                </div>"
          expected [{:url "/assets/images/bad1.jpg?size=notanumber"
                     :source-path "/assets/images/bad1.jpg"
                     :error "Invalid size format: notanumber"}
                    {:url "/assets/images/bad2.jpg?size=800xabc"
                     :source-path "/assets/images/bad2.jpg"
                     :error "Invalid height: abc"}
                    {:url "/assets/images/bad3.jpg?quality=high"
                     :source-path "/assets/images/bad3.jpg"
                     :replace-url "/assets/images/bad3.jpg"}]]
      (is (= expected (sg/extract-image-urls html)))))

  (testing "Extract from CSS files"
    (let [css ".team-member {
                 background-image: url('/assets/images/team/christine-nordal-sunde.jpg?size=400x400');
               }
               .hero {
                 background-image: linear-gradient(130deg, #003f7e4d, #3fb4984d), 
                                  url('/assets/images/hero-bg.svg?format=webp'), 
                                  url('/assets/images/hero-main.png?size=1920x1080&quality=90');
               }"
          expected [{:url "/assets/images/team/christine-nordal-sunde.jpg?size=400x400"
                     :source-path "/assets/images/team/christine-nordal-sunde.jpg"
                     :width 400 :height 400
                     :replace-url "/assets/images/team/christine-nordal-sunde-400x400.jpg"}
                    {:url "/assets/images/hero-bg.svg?format=webp"
                     :source-path "/assets/images/hero-bg.svg"
                     :replace-url "/assets/images/hero-bg.svg"}
                    {:url "/assets/images/hero-main.png?size=1920x1080&quality=90"
                     :source-path "/assets/images/hero-main.png"
                     :width 1920 :height 1080
                     :replace-url "/assets/images/hero-main-1920x1080.png"}]]
      (is (= expected (sg/extract-image-urls css)))))

  (testing "Skip external URLs in CSS"
    (let [css ".bg {
                 background-image: url('https://cdn.prod.website-files.com/bg.svg?size=100x100'),
                                  url('/assets/images/local.jpg?size=200x200');
               }"
          expected [{:url "/assets/images/local.jpg?size=200x200"
                     :source-path "/assets/images/local.jpg"
                     :width 200 :height 200
                     :replace-url "/assets/images/local-200x200.jpg"}]]
      (is (= expected (sg/extract-image-urls css))))))

(deftest test-eden-get
  (testing "Basic :eden/get replacement"
    (let [template [:div [:h1 [:eden/get :title]]]
          content {:data {:title "Welcome"}}
          expected [:div [:h1 "Welcome"]]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get looks in :data only"
    (let [template [:div [:h1 [:eden/get :title]]]
          content {:data {:title "Welcome"
                          :body [:p "Content"]}}
          expected [:div [:h1 "Welcome"]]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get with missing key returns visible indicator"
    (let [template [:div [:h1 [:eden/get :missing-key]]]
          content {:data {:title "Welcome"}}
          result (sg/process template content)]
      ;; Process returns wrapped format when warnings are generated
      (is (map? result))
      (is (= [:div [:h1 [:span.missing-content "[:eden/get :missing-key]"]]] (:result result)))
      (is (= 1 (count (:warnings result))))))

  (testing ":eden/get nested in attributes"
    (let [template [:a {:href [:eden/get :link]} "Click here"]
          content {:data {:link "/about.html"}}
          expected [:a {:href "/about.html"} "Click here"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get only gets single key (use :eden/get-in for nested)"
    (let [template [:div [:eden/get :user]]
          content {:data {:user {:name "John Doe"}}}
          expected [:div {:name "John Doe"}]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get combined with :eden/body"
    (let [template [:article
                    [:h1 [:eden/get :title]]
                    [:eden/body]]
          content {:body [:p "Article content"]
                   :data {:title "My Article"}}
          expected [:article
                    [:h1 "My Article"]
                    [:p "Article content"]]]
      (is (= expected (sg/process template content))))))

(deftest test-eden-each
  (testing "Basic :eden/each iteration"
    (let [template [:div
                    [:eden/each :news :limit 2
                     [:div.item [:eden/get :title]]]]
          content {:data {:news [{:title "News 1"}
                                 {:title "News 2"}
                                 {:title "News 3"}]}}
          expected [:div
                    [:div.item "News 1"]
                    [:div.item "News 2"]]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/each with ordering"
    (let [template [:ul
                    [:eden/each :items :order-by [:date :desc]
                     [:li [:eden/get :date] " - " [:eden/get :name]]]]
          content {:data {:items [{:date "2024-01-01" :name "First"}
                                  {:date "2024-03-01" :name "Third"}
                                  {:date "2024-02-01" :name "Second"}]}}
          expected [:ul
                    [:li "2024-03-01" " - " "Third"]
                    [:li "2024-02-01" " - " "Second"]
                    [:li "2024-01-01" " - " "First"]]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/each with nested template"
    (let [template [:section
                    [:eden/each :products :limit 2
                     [:article
                      [:h3 [:eden/get :name]]
                      [:p [:eden/get :description]]]]]
          content {:data {:products [{:name "Product A" :description "Description A"}
                                     {:name "Product B" :description "Description B"}
                                     {:name "Product C" :description "Description C"}]}}
          expected [:section
                    [:article
                     [:h3 "Product A"]
                     [:p "Description A"]]
                    [:article
                     [:h3 "Product B"]
                     [:p "Description B"]]]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/each with missing collection"
    (let [template [:div
                    [:eden/each :missing :limit 5
                     [:span "Item"]]]
          content {}
          expected [:div]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/each with :eden/include"
    (let [template [:div.news-grid
                    [:eden/each :news :limit 2
                     [:eden/include :news-card]]]
          content {:data {:news [{:title "News 1" :date "2024-04-15"}
                                 {:title "News 2" :date "2024-04-01"}]}
                   :templates {:news-card [:div.card
                                           [:h4 [:eden/get :title]]
                                           [:time [:eden/get :date]]]}}
          expected [:div.news-grid
                    [:div.card
                     [:h4 "News 1"]
                     [:time "2024-04-15"]]
                    [:div.card
                     [:h4 "News 2"]
                     [:time "2024-04-01"]]]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/each with complex ordering"
    (let [template [:div
                    [:eden/each :items :order-by [:priority :asc :name :desc]
                     [:div [:eden/get :priority] "-" [:eden/get :name]]]]
          content {:data {:items [{:priority 2 :name "B"}
                                  {:priority 1 :name "A"}
                                  {:priority 1 :name "C"}
                                  {:priority 2 :name "A"}]}}
          expected [:div
                    [:div 1 "-" "C"]
                    [:div 1 "-" "A"]
                    [:div 2 "-" "B"]
                    [:div 2 "-" "A"]]]
      (is (= expected (sg/process template content))))))

(deftest test-eden-each-all-content
  (testing "eden/each with :eden/all and :where filter"
    (let [context {:content-data {:news.article1 {:type "news"
                                                  :title "Article 1"
                                                  :date "2024-01-15"
                                                  :published true
                                                  :slug "article-1"}
                                  :news.article2 {:type "news"
                                                  :title "Article 2"
                                                  :date "2024-01-14"
                                                  :published true
                                                  :slug "article-2"}
                                  :news.draft {:type "news"
                                               :title "Draft Article"
                                               :date "2024-01-13"
                                               :published false
                                               :slug "draft"}
                                  :blog.post1 {:type "blog"
                                               :title "Blog Post"
                                               :published true
                                               :slug "blog-1"}
                                  :about {:type "page"
                                          :title "About"
                                          :slug "about"}}}
          template [:eden/each :eden/all :where {:type "news" :published true} :order-by [:date :desc]
                    [:article [:h3 [:eden/get :title]]]]
          result (sg/process-element template context)]
      (is (= 2 (count result)))
      (is (= [:article [:h3 "Article 1"]] (first result)))
      (is (= [:article [:h3 "Article 2"]] (second result)))))

  (testing "eden/each :eden/all without where gets all content"
    (let [context {:content-data {:page1 {:title "Page 1"}
                                  :page2 {:title "Page 2"}
                                  :page3 {:title "Page 3"}}}
          template [:eden/each :eden/all :limit 2
                    [:div [:eden/get :title]]]
          result (sg/process-element template context)]
      (is (= 2 (count result))))) ; Limited to 2

  (testing "eden/each with context key (current behavior)"
    (let [context {:data {:items [{:id 1} {:id 2}]}}
          template [:eden/each :items
                    [:span [:eden/get :id]]]
          result (sg/process-element template context)]
      (is (= 2 (count result)))
      (is (= [:span 1] (first result)))
      (is (= [:span 2] (second result)))))

  (testing "eden/each with :eden/all and empty filter returns empty"
    (let [context {:content-data {:page1 {:type "other"}
                                  :page2 {:type "other"}}}
          template [:eden/each :eden/all :where {:type "news"}
                    [:article [:eden/get :title]]]
          result (sg/process-element template context)]
      (is (= [] result))))

  (testing "eden/each with :where multiple criteria"
    (let [context {:content-data {:news.featured {:type "news"
                                                  :featured true
                                                  :published true
                                                  :title "Featured"}
                                  :news.regular {:type "news"
                                                 :featured false
                                                 :published true
                                                 :title "Regular"}
                                  :blog.featured {:type "blog"
                                                  :featured true
                                                  :published true
                                                  :title "Blog Featured"}}}
          template [:eden/each :eden/all :where {:type "news" :featured true}
                    [:article [:eden/get :title]]]
          result (sg/process-element template context)]
      (is (= 1 (count result)))
      (is (= [:article "Featured"] (first result)))))

  (testing "eden/each :eden/all with single criterion"
    (let [context {:content-data {:news.1 {:type "news" :title "News 1"}
                                  :news.2 {:type "news" :title "News 2"}
                                  :blog.1 {:type "blog" :title "Blog 1"}}}
          template [:eden/each :eden/all :where {:type "news"}
                    [:li [:eden/get :title]]]
          result (sg/process-element template context)]
      (is (= 2 (count result)))
      (is (every? #(str/includes? (str %) "News") result)))))

(deftest test-eden-each-map-iteration
  (testing "Map with primitive values requires :eden.each/value"
    (let [template [:div
                    [:eden/each :config
                     [:p [:eden/get :eden.each/key] "=" [:eden/get :eden.each/value]]]]
          context {:data {:config {:host "localhost" :port 8080}}}
          result (sg/process template context)
          ;; Order might vary, so check both possibilities
          possible1 [:div [:p :host "=" "localhost"] [:p :port "=" 8080]]
          possible2 [:div [:p :port "=" 8080] [:p :host "=" "localhost"]]]
      (is (or (= possible1 result) (= possible2 result)))))

  (testing "Map iteration with index"
    (let [template [:eden/each :scores
                    [:div
                     [:eden/get :eden.each/index] ". "
                     [:eden/get :eden.each/key] "="
                     [:eden/get :eden.each/value]]]
          context {:data {:scores {:a 1 :b 2}}}
          result (sg/process template context)]
      ;; Each item should have an index
      (is (= 2 (count result)))
      (is (some #(and (vector? %) (= ". " (nth % 2))) result))))

  (testing "Map with map values merges into context"
    (let [template [:eden/each :users
                    [:div
                     "User " [:eden/get :eden.each/key] " is "
                     [:eden/get :age] " years old"]]
          context {:data {:users {:alice {:age 30 :city "NYC"}
                                  :bob {:age 25 :city "LA"}}}}
          result (sg/process template context)]
      (is (= 2 (count result)))
      (is (some #(= % [:div "User " :alice " is " 30 " years old"]) result))
      (is (some #(= % [:div "User " :bob " is " 25 " years old"]) result))))

  (testing "Real-world example: products by category"
    (let [template [:eden/each :products
                    [:section
                     [:h2 [:eden/get :title]]
                     [:p "Category ID: " [:eden/get :eden.each/key]]
                     [:ul
                      [:eden/each :items
                       [:li [:eden/get :name]]]]]]
          context {:data {:products {:logistics {:title "Anteo Logistikk"
                                                 :items [{:name "Kartverktøy"}
                                                         {:name "Logifish"}]}
                                     :fish-health {:title "Anteo Fiskehelse"
                                                   :items [{:name "FishCtrl"}
                                                           {:name "FishJrnl"}]}}}}
          result (sg/process template context)]
      (is (= 2 (count result)))
      ;; Check that we can access both the merged fields and the key
      (is (every? #(and (= :section (first %))
                        (= :h2 (first (second %)))
                        ;; Has category ID paragraph with keyword
                        (some (fn [elem]
                                (and (vector? elem)
                                     (= :p (first elem))
                                     (= "Category ID: " (second elem))
                                     (keyword? (nth elem 2))))
                              %))
                  result))))

  (testing "Map with non-map values requires :eden.each/value"
    (let [template [:eden/each :scores
                    [:li [:eden/get :eden.each/key] ": " [:eden/get :eden.each/value]]]
          context {:data {:scores {:math 95 :english 87 :science 92}}}
          result (sg/process template context)]
      (is (= 3 (count result)))
      (is (every? #(and (= :li (first %))
                        (keyword? (second %)) ; Keys are keywords
                        (number? (nth % 3))) result))))

  (testing "Map iteration with :order-by :eden.each/key"
    (let [template [:eden/each :items :order-by [:eden.each/key :asc]
                    [:li [:eden/get :eden.each/key]]]
          context {:data {:items {:zebra {:val 1} :apple {:val 2} :mouse {:val 3}}}}
          expected [[:li :apple] [:li :mouse] [:li :zebra]]]
      (is (= expected (sg/process template context)))))

  (testing "Map iteration with :order-by on merged field"
    (let [template [:eden/each :users :order-by [:age :desc]
                    [:div [:eden/get :name] " (" [:eden/get :age] ")"]]
          context {:data {:users {:u1 {:name "Alice" :age 30}
                                  :u2 {:name "Bob" :age 40}
                                  :u3 {:name "Charlie" :age 20}}}}
          expected [[:div "Bob" " (" 40 ")"]
                    [:div "Alice" " (" 30 ")"]
                    [:div "Charlie" " (" 20 ")"]]]
      (is (= expected (sg/process template context)))))

  (testing "Map iteration with :limit"
    (let [template [:eden/each :data :limit 2
                    [:span [:eden/get :eden.each/key]]]
          context {:data {:data {:a {:val 1} :b {:val 2} :c {:val 3} :d {:val 4}}}}
          result (sg/process template context)]
      (is (= 2 (count result)))
      (is (every? #(keyword? (second %)) result)))) ; Keys should be keywords

  (testing "Empty map returns empty vector"
    (let [template [:div [:eden/each :empty-map
                          [:p "Should not appear"]]]
          context {:data {:empty-map {}}}
          expected [:div]]
      (is (= expected (sg/process template context)))))

  (testing "Map with nil values"
    (let [template [:eden/each :config
                    [:p [:eden/get :eden.each/key] "=" [:eden/get :eden.each/value "none"]]]
          context {:data {:config {:foo nil :bar "value"}}}
          result (sg/process template context)]
      (is (= 2 (count result)))
      (is (some #(= % [:p :foo "=" "none"]) result))
      (is (some #(= % [:p :bar "=" "value"]) result)))))

(deftest test-eden-each-index-for-sequences
  (testing "Sequence iteration with index"
    (let [template [:ul
                    [:eden/each :items
                     [:li "Item " [:eden/get :eden.each/index] ": " [:eden/get :name]]]]
          context {:data {:items [{:name "First"} {:name "Second"} {:name "Third"}]}}
          expected [:ul
                    [:li "Item " 0 ": " "First"]
                    [:li "Item " 1 ": " "Second"]
                    [:li "Item " 2 ": " "Third"]]]
      (is (= expected (sg/process template context)))))

  (testing "Index with limit"
    (let [template [:eden/each :items :limit 2
                    [:div "Index: " [:eden/get :eden.each/index]]]
          context {:data {:items ["a" "b" "c" "d"]}}
          expected [[:div "Index: " 0]
                    [:div "Index: " 1]]]
      (is (= expected (sg/process template context)))))

  (testing "Index with order-by (index follows sorted order)"
    (let [template [:eden/each :items :order-by [:value :desc]
                    [:div [:eden/get :eden.each/index] ": " [:eden/get :value]]]
          context {:data {:items [{:value 10} {:value 30} {:value 20}]}}
          expected [[:div 0 ": " 30]
                    [:div 1 ": " 20]
                    [:div 2 ": " 10]]]
      (is (= expected (sg/process template context)))))

  (testing "Index in grouped items"
    (let [template [:eden/each :items :group-by :category
                    [:div
                     [:h3 [:eden/get :eden.each/group-key]]
                     [:eden/each :eden.each/group-items
                      [:p "Item " [:eden/get :eden.each/index] ": " [:eden/get :name]]]]]
          context {:data {:items [{:category "A" :name "A1"}
                                  {:category "B" :name "B1"}
                                  {:category "A" :name "A2"}]}}
          result (sg/process template context)]
      ;; Each group should have its own index starting from 0
      (is (= 2 (count result)))
      (is (every? #(and (= :div (first %))
                        (= :h3 (first (second %)))
                        ;; Check that indices exist in the paragraphs
                        (some (fn [elem]
                                (and (vector? elem)
                                     (= :p (first elem))
                                     (= "Item " (second elem))
                                     (number? (nth elem 2))))
                              %))
                  result)))))

(deftest test-eden-each-where-filter-with-regular-collection
  (testing ":where filter should work with regular collections (not just :eden/all)"
    (let [template [:eden/each :posts
                    :where {:published true :type :blog}
                    [:article [:eden/get :title]]]
          context {:data {:posts [{:title "Blog Post 1" :published true :type :blog}
                                  {:title "Blog Post 2" :published false :type :blog}
                                  {:title "News Item" :published true :type :news}
                                  {:title "Blog Post 3" :published true :type :blog}]}}
          ;; Expected: only published blog posts
          expected [[:article "Blog Post 1"]
                    [:article "Blog Post 3"]]
          actual (sg/process template context)]
      (is (= expected actual)
          "Should filter regular collections by :where conditions")))

  (testing ":where filter with multiple conditions (AND logic)"
    (let [template [:eden/each :products
                    :where {:category :electronics :featured true}
                    [:div [:eden/get :name]]]
          context {:data {:products [{:name "Phone" :category :electronics :featured true}
                                     {:name "Laptop" :category :electronics :featured false}
                                     {:name "Tablet" :category :electronics :featured true}
                                     {:name "Book" :category :books :featured true}]}}
          ;; Expected: only featured electronics
          expected [[:div "Phone"]
                    [:div "Tablet"]]
          actual (sg/process template context)]
      (is (= expected actual)
          "Should apply ALL conditions in :where clause (AND logic)"))))

(deftest test-eden-each-group-by
  (testing ":eden/each with :group-by groups items by field"
    (let [template [:div
                    [:eden/each :products :group-by :category
                     [:div.category-section
                      [:h3 [:eden/get :eden.each/group-key]]
                      [:eden/each :eden.each/group-items
                       [:div.product [:eden/get :title]]]]]]
          context {:data {:products [{:title "Logifish" :category :logistics}
                                     {:title "FishJrnl" :category :fish-health}
                                     {:title "Kartverktøy" :category :logistics}
                                     {:title "FishCtrl" :category :fish-health}]}}
          result (sg/process template context)]
      ;; Should group by category and maintain order
      (is (= [:div
              [:div.category-section
               [:h3 :logistics]
               [:div.product "Logifish"]
               [:div.product "Kartverktøy"]]
              [:div.category-section
               [:h3 :fish-health]
               [:div.product "FishJrnl"]
               [:div.product "FishCtrl"]]]
             result))))

  (testing ":eden/each with :group-by and :order-by on groups"
    (let [template [:div
                    [:eden/each :items :group-by :type :order-by [:group-key :desc]
                     [:section
                      [:h4 [:eden/get :eden.each/group-key]]
                      [:eden/each :eden.each/group-items
                       [:p [:eden/get :name]]]]]]
          context {:data {:items [{:name "A" :type :beta}
                                  {:name "B" :type :alpha}
                                  {:name "C" :type :beta}
                                  {:name "D" :type :alpha}]}}
          result (sg/process template context)]
      ;; Groups should be ordered by key descending (beta before alpha)
      (is (= [:div
              [:section
               [:h4 :beta]
               [:p "A"]
               [:p "C"]]
              [:section
               [:h4 :alpha]
               [:p "B"]
               [:p "D"]]]
             result))))

  (testing ":eden/each with :group-by and :limit on groups"
    (let [template [:div
                    [:eden/each :items :group-by :category :limit 1
                     [:div [:eden/get :eden.each/group-key]]]]
          context {:data {:items [{:category :a}
                                  {:category :b}
                                  {:category :a}
                                  {:category :c}]}}
          result (sg/process template context)]
      ;; Should only show first group
      (is (= [:div [:div :a]] result))))

  (testing ":eden/each with :group-by on nested path"
    (let [template [:div
                    [:eden/each :products :group-by [:meta :category]
                     [:div.group
                      [:h3 [:eden/get :eden.each/group-key]]
                      [:eden/each :eden.each/group-items
                       [:span [:eden/get :name]]]]]]
          context {:data {:products [{:name "P1" :meta {:category "tools"}}
                                     {:name "P2" :meta {:category "parts"}}
                                     {:name "P3" :meta {:category "tools"}}]}}
          result (sg/process template context)]
      (is (= [:div
              [:div.group
               [:h3 "tools"]
               [:span "P1"]
               [:span "P3"]]
              [:div.group
               [:h3 "parts"]
               [:span "P2"]]]
             result))))

  (testing ":eden/each with :group-by handles missing values"
    (let [template [:div
                    [:eden/each :items :group-by :category
                     [:div
                      [:h4 [:eden/get :eden.each/group-key "No category"]]
                      [:eden/each :eden.each/group-items
                       [:p [:eden/get :title]]]]]]
          context {:data {:items [{:title "A" :category :cat1}
                                  {:title "B"} ; Missing category
                                  {:title "C" :category :cat1}
                                  {:title "D"}]}} ; Missing category
          result (sg/process template context)]
      ;; Items without the group field should be grouped under nil
      (is (= [:div
              [:div
               [:h4 :cat1]
               [:p "A"]
               [:p "C"]]
              [:div
               [:h4 "No category"]
               [:p "B"]
               [:p "D"]]]
             result))))

  (testing ":eden/each with :group-by and ordering within groups"
    (let [template [:div
                    [:eden/each :products :group-by :category
                     [:div.category
                      [:h3 [:eden/get :eden.each/group-key]]
                      [:eden/each :eden.each/group-items :order-by [:price :asc]
                       [:div [:eden/get :title] " - $" [:eden/get :price]]]]]]
          context {:data {:products [{:title "Expensive" :category :tools :price 200}
                                     {:title "Cheap" :category :tools :price 50}
                                     {:title "Premium" :category :parts :price 500}
                                     {:title "Budget" :category :parts :price 100}
                                     {:title "Medium" :category :tools :price 100}]}}
          result (sg/process template context)]
      ;; Items within each group should be ordered by price
      (is (= [:div
              [:div.category
               [:h3 :tools]
               [:div "Cheap" " - $" 50]
               [:div "Medium" " - $" 100]
               [:div "Expensive" " - $" 200]]
              [:div.category
               [:h3 :parts]
               [:div "Budget" " - $" 100]
               [:div "Premium" " - $" 500]]]
             result)))))

; test-eden-each-group-by-no-shadowing removed - shadowing is no longer possible with namespaced keys

(testing ":eden/get in nested vector structure (like landing template)"
  (let [template [[:section.hero [:h1 [:eden/get :hero-title]]]
                  [:section.main [:h2 [:eden/get :hero-subtitle]]]]
        content {:data {:hero-title "Title Text"
                        :hero-subtitle "Subtitle Text"}}
        expected [[:section.hero [:h1 "Title Text"]]
                  [:section.main [:h2 "Subtitle Text"]]]]
    (is (= expected (sg/process template content)))))

(deftest test-vector-of-vectors-processing
  (testing "Processing a vector of elements"
    (let [template [[:h1 [:eden/get :title]]
                    [:p [:eden/get :description]]]
          content {:data {:title "Welcome"
                          :description "Hello world"}}
          expected [[:h1 "Welcome"]
                    [:p "Hello world"]]]
      (is (= expected (sg/process template content)))))

  (testing "Processing nested vectors with :eden/body"
    (let [wrapper [:div [:eden/body]]
          content {:body [[:h1 "Title"]
                          [:p "Paragraph"]]}
          expected [:div [:h1 "Title"] [:p "Paragraph"]]]
      (is (= expected (sg/process wrapper content))))

    (testing "should splice vector of vectors"
      (let [wrapper [:article [:eden/body]]
            content {:body [[:h1 "Title"]
                            [:p "First"]
                            [:p "Second"]]}
            expected [:article [:h1 "Title"] [:p "First"] [:p "Second"]]]
        (is (= expected (sg/process wrapper content))))))

  (testing "Processing :eden/get in vector of elements"
    (let [template [[:h1 [:eden/get :title]]
                    [:section [:p [:eden/get :intro]]]
                    [:footer "Copyright"]]
          content {:data {:title "My Page"
                          :intro "Welcome to our site"}}
          expected [[:h1 "My Page"]
                    [:section [:p "Welcome to our site"]]
                    [:footer "Copyright"]]]
      (is (= expected (sg/process template content)))))

  (testing "Combining :eden/body with vector of elements"
    (let [template [:main
                    [:header [:h1 [:eden/get :title]]]
                    [:eden/body]
                    [:footer "© 2024"]]
          content {:data {:title "Page Title"}
                   :body [[:section [:p "Content 1"]]
                          [:section [:p "Content 2"]]]}
          expected [:main
                    [:header [:h1 "Page Title"]]
                    [:section [:p "Content 1"]]
                    [:section [:p "Content 2"]]
                    [:footer "© 2024"]]]
      (is (= expected (sg/process template content)))))

  (testing "Vector template with includes"
    (let [template [[:header [:eden/include :nav]]
                    [:main [:eden/body]]
                    [:eden/include :footer]]
          content {:body [:p "Main content"]
                   :templates {:nav [:nav "Navigation"]
                               :footer [:footer "Footer text"]}}
          expected [[:header [:nav "Navigation"]]
                    [:main [:p "Main content"]]
                    [:footer "Footer text"]]]
      (is (= expected (sg/process template content))))))

(deftest test-eden-body-with-eden-each
  (testing ":eden/body with vector of vectors containing :eden/each"
    (let [base-template [:main [:eden/body]]
          body-content [[:h1 "Title"]
                        [:eden/each :items [:div [:eden/get :name]]]
                        [:p "Footer"]]
          content {:body body-content
                   :data {:items [{:name "Item 1"} {:name "Item 2"}]}}
          expected [:main
                    [:h1 "Title"]
                    [:div "Item 1"]
                    [:div "Item 2"]
                    [:p "Footer"]]
          actual (sg/process base-template content)]
      (is (= expected actual)))))

(deftest test-eden-get-in-attributes
  (testing ":eden/get in attributes when value exists"
    (let [template [:div {:class [:eden/get :wrap-class]} "Content"]
          content {:data {:wrap-class "reversed"}}
          expected [:div {:class "reversed"} "Content"]
          actual (sg/process template content)]
      (is (= expected actual)))))

(deftest test-eden-each-in-vector-of-vectors
  (testing ":eden/each in a vector of vectors should splice results properly"
    (let [;; Template is a vector of vectors (like landing.edn)
          template [[:h1 "Title"]
                    [:eden/each :products
                     [:section [:eden/get :name]]]]
          content {:data {:products [{:name "P1"} {:name "P2"}]}}

          ;; Process the template
          result (sg/process template content)

          ;; We expect the products to be spliced at the same level
          expected [[:h1 "Title"]
                    [:section "P1"]
                    [:section "P2"]]]
      (is (= expected result)))))

(deftest test-eden-get-missing-value
  (testing ":eden/get with missing value should return visible indicator"
    (let [template [:div [:eden/get :missing-key]]
          content {:other-key "value"}
          result (sg/process template content)]
      ;; Process returns wrapped format when warnings are generated
      (is (map? result))
      (is (= [:div [:span.missing-content "[:eden/get :missing-key]"]] (:result result)))
      (is (= 1 (count (:warnings result))))))

  (testing ":eden/get missing key returns visible indicator (not for nested)"
    (let [template [:div [:eden/get :deeply]]
          content {:other {:nested "value"}}
          result (sg/process template content)]
      (is (map? result))
      (is (= [:div [:span.missing-content "[:eden/get :deeply]"]] (:result result))
          "Missing key should return visible indicator")
      (is (= 1 (count (:warnings result))))))

  (testing ":eden/get in attributes with missing value"
    (let [template [:div {:class [:eden/get :missing-class]} "Content"]
          content {}
          result (sg/process template content)]
      (is (map? result))
      (is (= [:div {:class [:span.missing-content "[:eden/get :missing-class]"]} "Content"] (:result result)))
      (is (= 1 (count (:warnings result)))))))

(deftest test-eden-get-with-default
  (testing ":eden/get with default value when key is missing"
    (let [template [:div {:class [:eden/get :wrap-class ""]} "Content"]
          content {:data {:title "Product"}}
          expected [:div {:class ""} "Content"]
          actual (sg/process template content)]
      (is (= expected actual) "Should return default empty string when key is missing")))

  (testing ":eden/get with default value when key exists"
    (let [template [:div {:class [:eden/get :wrap-class ""]} "Content"]
          content {:data {:wrap-class "reversed"}}
          expected [:div {:class "reversed"} "Content"]
          actual (sg/process template content)]
      (is (= expected actual) "Should return actual value when key exists")))

  (testing ":eden/get with keyword as default value"
    (let [template [:div [:eden/get :missing-key :default-keyword]]
          content {:data {:other "value"}}
          expected [:div :default-keyword]
          actual (sg/process template content)]
      (is (= expected actual) "Should support keyword as default value"))))

(deftest test-eden-if
  (testing ":eden/if with truthy condition shows then-branch"
    (let [template [:div [:eden/if :show-content [:p "Content visible"]]]
          content {:data {:show-content true}}
          result (sg/process template content)]
      (is (= [:div [:p "Content visible"]] result))))

  (testing ":eden/if with falsy condition returns nil"
    (let [template [:div [:eden/if :show-content [:p "Content visible"]]]
          content {:data {:show-content false}}
          result (sg/process template content)]
      (is (= [:div nil] result))))

  (testing ":eden/if with else-branch"
    (let [template [:div [:eden/if :premium
                          [:p "Premium content"]
                          [:p "Standard content"]]]
          content {:data {:premium false}}
          result (sg/process template content)]
      (is (= [:div [:p "Standard content"]] result))))

  (testing ":eden/if with nested path condition"
    (let [template [:div [:eden/if [:user :admin]
                          [:button "Delete"]]]
          content {:data {:user {:admin true}}}
          result (sg/process template content)]
      (is (= [:div [:button "Delete"]] result))))

  (testing ":eden/if with nil value is falsy"
    (let [template [:div [:eden/if :missing-key
                          [:p "Should not show"]
                          [:p "Fallback"]]]
          content {}
          result (sg/process template content)]
      (is (= [:div [:p "Fallback"]] result))))

  (testing ":eden/if with direct boolean value"
    (let [template [:div [:eden/if true [:p "Always shows"]]]
          content {}
          result (sg/process template content)]
      (is (= [:div [:p "Always shows"]] result))))

  (testing ":eden/if can contain other directives"
    (let [template [:div [:eden/if :show-user
                          [:p "Hello, " [:eden/get :username]]]]
          content {:data {:show-user true :username "Alice"}}
          result (sg/process template content)]
      (is (= [:div [:p "Hello, " "Alice"]] result))))

  (testing "Nested :eden/if directives"
    (let [template [:div [:eden/if :level1
                          [:div [:eden/if :level2
                                 [:p "Both conditions met"]]]]]
          content {:data {:level1 true :level2 true}}
          result (sg/process template content)]
      (is (= [:div [:div [:p "Both conditions met"]]] result)))

    (let [content {:data {:level1 true :level2 false}}
          template [:div [:eden/if :level1
                          [:div [:eden/if :level2
                                 [:p "Both conditions met"]]]]]
          result (sg/process template content)]
      (is (= [:div [:div nil]] result))))

  (testing ":eden/if with empty string is truthy (Clojure semantics)"
    (let [template [:div [:eden/if :message
                          [:p "Message: " [:eden/get :message]]
                          [:p "No message"]]]
          content {:data {:message ""}}
          result (sg/process template content)]
      (is (= [:div [:p "Message: " ""]] result))))

  (testing ":eden/if with non-empty string is truthy"
    (let [template [:div [:eden/if :message
                          [:p [:eden/get :message]]]]
          content {:data {:message "Hello"}}
          result (sg/process template content)]
      (is (= [:div [:p "Hello"]] result))))

  (testing ":eden/if with empty collection is truthy (Clojure semantics)"
    (let [template [:div [:eden/if :items
                          [:ul [:eden/each :items [:li [:eden/get :name]]]]
                          [:p "No items"]]]
          content {:data {:items []}}
          result (sg/process template content)]
      ;; Empty collection is truthy in Clojure
      (is (= [:div [:ul]] result))))

  (testing ":eden/if with non-empty collection is truthy"
    (let [template [:div [:eden/if :items
                          [:ul [:eden/each :items [:li [:eden/get :name]]]]
                          [:p "No items"]]]
          content {:data {:items [{:name "Item 1"} {:name "Item 2"}]}}
          result (sg/process template content)]
      (is (= [:div [:ul [:li "Item 1"] [:li "Item 2"]]] result))))

  (testing ":eden/if checking for nil vs false"
    (let [template [:div [:eden/if :flag
                          [:p "Flag is set"]
                          [:p "Flag is not set"]]]
          ;; nil is falsy
          result-nil (sg/process template {})
          ;; false is falsy
          result-false (sg/process template {:data {:flag false}})
          ;; true is truthy
          result-true (sg/process template {:data {:flag true}})]
      (is (= [:div [:p "Flag is not set"]] result-nil))
      (is (= [:div [:p "Flag is not set"]] result-false))
      (is (= [:div [:p "Flag is set"]] result-true)))))

(deftest test-eden-get-content-namespace
  (testing ":eden/get with content/* namespace automatically wraps HTML strings"
    (let [template [:div [:eden/get :content/html]]
          html-string "<h1>Test</h1><p>Content</p>"
          content {:data {:content/html html-string}}
          result (sg/process template content)]
      ;; Result should contain a RawString object
      (is (vector? result))
      (is (= :div (first result)))
      (is (= html-string (str (second result))))))

  (testing ":eden/get with content/* namespace but non-string value"
    (let [template [:div [:eden/get :content/data]]
          content {:data {:content/data {:foo "bar"}}}
          result (sg/process template content)]
      ;; Non-strings should not be wrapped
      (is (= [:div {:foo "bar"}] result))))

  (testing ":eden/get with non-content namespace doesn't wrap strings"
    (let [template [:div [:eden/get :title]]
          content {:data {:title "Plain text"}}
          result (sg/process template content)]
      ;; Regular strings should not be wrapped
      (is (= [:div "Plain text"] result))))

  (testing ":eden/get with content/* namespace (single key only now)"
    (let [template [:div [:eden/get :content/sections]]
          content {:data {:content/sections {:intro "<p>Intro text</p>"}}}
          result (sg/process template content)]
      ;; Single key access, but not automatically wrapped since it's not a string
      (is (= [:div {:intro "<p>Intro text</p>"}] result)))))

(deftest test-eden-t
  (testing ":eden/t basic translation"
    (let [template [:h1 [:eden/t :nav/home]]
          context {:lang :no
                   :strings {:nav/home "Forsiden"}}
          result (sg/process template context)]
      (is (= [:h1 "Forsiden"] result))))

  (testing ":eden/t with English"
    (let [template [:h1 [:eden/t :nav/home]]
          context {:lang :en
                   :strings {:nav/home "Home"}}
          result (sg/process template context)]
      (is (= [:h1 "Home"] result))))

  (testing ":eden/t with interpolation"
    (let [template [:footer [:eden/t :footer/copyright {:year 2024}]]
          context {:lang :no
                   :strings {:footer/copyright "© {{year}} Anteo AS"}}
          result (sg/process template context)]
      (is (= [:footer "© 2024 Anteo AS"] result))))

  (testing ":eden/t with multiple interpolations"
    (let [template [:p [:eden/t :greeting {:name "Ole" :day "mandag"}]]
          context {:lang :no
                   :strings {:greeting "Hei {{name}}, ha en fin {{day}}!"}}
          result (sg/process template context)]
      (is (= [:p "Hei Ole, ha en fin mandag!"] result))))

  (testing ":eden/t with missing translation key"
    (let [template [:span [:eden/t :missing/key]]
          context {:lang :no
                   :strings {:other/key "Something"}}
          result (sg/process template context)]
      ;; Should return result with warnings (like :eden/link does for missing pages)
      (is (map? result))
      (is (contains? result :result))
      (is (contains? result :warnings))
      (is (= [:span "### :missing/key ###"] (:result result)))
      ;; Should collect warning
      (is (= 1 (count (:warnings result))))
      (is (= :missing-translation (-> result :warnings first :type)))
      (is (= :missing/key (-> result :warnings first :key)))
      (is (= :no (-> result :warnings first :lang)))))

  (testing ":eden/t with default fallback"
    (let [template [:span [:eden/t :missing/key "Default text"]]
          context {:lang :no
                   :strings {:other/key "Something"}}
          result (sg/process template context)]
      (is (= [:span "Default text"] result))))

  (testing ":eden/t nested in attributes"
    (let [template [:img {:alt [:eden/t :common/logo]}]
          context {:lang :no
                   :strings {:common/logo "Anteo logo"}}
          result (sg/process template context)]
      (is (= [:img {:alt "Anteo logo"}] result))))

  (testing ":eden/t with nested templates"
    (let [template [:div
                    [:h1 [:eden/t :page/title]]
                    [:p [:eden/t :page/intro]]]
          context {:lang :no
                   :strings {:page/title "Velkommen"
                             :page/intro "Dette er en introduksjon"}}
          result (sg/process template context)]
      (is (= [:div
              [:h1 "Velkommen"]
              [:p "Dette er en introduksjon"]] result)))))

(deftest test-eden-t-with-interpolation-directives
  (testing ":eden/t with [:eden/get] directives in interpolation"
    (let [template [:eden/t :footer/copyright {:year [:eden/get :current-year]}]
          context {:strings {:footer/copyright "© {{year}} Company"}
                   :data {:current-year "2025"}}
          result (sg/process template context)]
      (is (= "© 2025 Company" result)
          "Should process [:eden/get] directives in interpolation values")))

  (testing ":eden/t with multiple interpolation directives"
    (let [template [:eden/t :greeting {:name [:eden/get :user/name]
                                       :day [:eden/get :current-day]}]
          context {:strings {:greeting "Hello {{name}}, happy {{day}}!"}
                   :data {:user/name "Alice"
                          :current-day "Monday"}}
          result (sg/process template context)]
      (is (= "Hello Alice, happy Monday!" result)
          "Should process multiple [:eden/get] directives")))

  (testing ":eden/t with plain values mixed with directives"
    (let [template [:eden/t :message {:static "World"
                                      :dynamic [:eden/get :value]}]
          context {:strings {:message "Hello {{static}}, value is {{dynamic}}"}
                   :data {:value "42"}}
          result (sg/process template context)]
      (is (= "Hello World, value is 42" result)
          "Should handle both plain values and directives"))))

(deftest test-eden-link
  (testing ":eden/link basic usage (keyword expands to {:page-id ...})"
    (let [template [:eden/link :privacy
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [] ; at root
                   :pages {:no {:privacy {:slug "personvern"
                                          :title "Personvern"}}}}
          result (sg/process template context)]
      (is (= [:a {:href "/personvern"} "Personvern"] result))))

  (testing ":eden/link with English"
    (let [template [:eden/link :privacy
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :en
                   :path [] ; at root
                   :pages {:en {:privacy {:slug "privacy"
                                          :title "Privacy"}}}}
          result (sg/process template context)]
      (is (= [:a {:href "/en/privacy"} "Privacy"] result))))

  (testing ":eden/link with {:nav :parent}"
    (let [template [:eden/link {:nav :parent}
                    [:a.back-link {:href [:eden/get :link/href]} "Back to " [:eden/get :link/title]]]
          context {:lang :no
                   :path [:news :article] ; at /news/article
                   :pages {:no {:news {:slug "news"
                                       :title "Aktuelt"}}}}
          result (sg/process template context)]
      (is (= [:a.back-link {:href "/news"} "Back to " "Aktuelt"] result))))

  (testing ":eden/link with {:nav :parent} and translations"
    (let [template [:eden/link {:nav :parent}
                    [:a.back-link {:href [:eden/get :link/href]}
                     [:eden/t :nav/back-to] " " [:eden/get :link/title]]]
          context {:lang :no
                   :path [:privacy] ; at /privacy, parent is root
                   :strings {:nav/back-to "← Tilbake til"}
                   :pages {:no {:landing {:slug ""
                                          :title "Forsiden"}}}}
          result (sg/process template context)]
      (is (= [:a.back-link {:href "/"}
              "← Tilbake til" " " "Forsiden"] result))))

  (testing ":eden/link with missing page"
    (let [template [:eden/link :missing
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path []
                   :pages {:other {:slug "other"
                                   :title "Other"}}}
          result (sg/process template context)]
      ;; Should handle gracefully and return wrapped format
      (is (map? result))
      (is (= [:a {:href "#"} "missing"] (:result result)))
      (is (= 1 (count (:warnings result))))
      (is (= :missing-page (-> result :warnings first :type)))))

  (testing ":eden/link with {:nav :parent} at root returns nil"
    (let [template [:eden/link {:nav :parent}
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [] ; at root, no parent
                   :pages {}}
          result (sg/process template context)]
      ;; Should return nil or handle gracefully
      (is (nil? result))))

  (testing ":eden/link in navigation"
    (let [template [:nav
                    [:ul
                     [:li [:eden/link :home
                           [:a {:href [:eden/get :link/href]} [:eden/t :nav/home]]]]
                     [:li [:eden/link :about
                           [:a {:href [:eden/get :link/href]} [:eden/t :nav/about]]]]]]
          context {:lang :no
                   :path [:products] ; current location
                   :strings {:nav/home "Hjem"
                             :nav/about "Om oss"}
                   :pages {:no {:home {:slug ""
                                       :title "Forsiden"}
                                :about {:slug "om-oss"
                                        :title "Om oss"}}}}
          result (sg/process template context)]
      (is (= [:nav
              [:ul
               [:li [:a {:href "/"} "Hjem"]]
               [:li [:a {:href "/om-oss"} "Om oss"]]]] result))))

  (testing ":eden/link with complex body"
    (let [template [:eden/link :products
                    [:div.card
                     [:a {:href [:eden/get :link/href]}
                      [:h3 [:eden/get :link/title]]
                      [:p [:eden/t :common/read-more]]]]]
          context {:lang :no
                   :path [:news :article] ; current location
                   :strings {:common/read-more "Les mer"}
                   :pages {:no {:products {:slug "produkter"
                                           :title "Våre produkter"}}}}
          result (sg/process template context)]
      (is (= [:div.card
              [:a {:href "/produkter"}
               [:h3 "Våre produkter"]
               [:p "Les mer"]]] result))))

  (testing ":eden/link with conditional parent"
    (let [template [:eden/if [:eden/get :has-parent]
                    [:eden/link {:nav :parent}
                     [:a.back {:href [:eden/get :link/href]} [:eden/get :link/title]]]]
          context {:data {:has-parent true}
                   :lang :no
                   :path [:about] ; has parent (root)
                   :pages {:no {:landing {:slug ""
                                          :title "Forsiden"}}}}
          result (sg/process template context)]
      (is (= [:a.back {:href "/"} "Forsiden"] result))))

  (testing ":eden/link {:nav :parent} with nested content"
    (let [template [:eden/link {:nav :parent}
                    [:a.back-link {:href [:eden/get :link/href]}
                     [:eden/t :nav/back-to] " " [:eden/get :link/title]]]
          context {:lang :no
                   :path [:news :my-article] ; at /news/my-article
                   :strings {:nav/back-to "← Tilbake til"}
                   :pages {:no {:news {:slug "news"
                                       :title "Aktuelt"}}}}
          result (sg/process template context)]
      ;; Should use absolute path
      (is (= [:a.back-link {:href "/news"}
              "← Tilbake til" " " "Aktuelt"] result))))

  (testing ":eden/link {:nav :parent} with deeply nested content"
    (let [template [:eden/link {:nav :parent}
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [:products :logistics :logifish] ; at /products/logistics/logifish
                   :pages {:no {:logistics {:slug "products/logistics"
                                            :title "Logistikk"}}}}
          result (sg/process template context)]
      ;; Parent is [:products :logistics], should resolve to /products/logistics
      (is (= [:a {:href "/products/logistics"} "Logistikk"] result))))

  (testing ":eden/link from nested to root"
    (let [template [:eden/link :home
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [:news :article] ; at /news/article
                   :pages {:no {:home {:slug ""
                                       :title "Forsiden"}}}}
          result (sg/process template context)]
      ;; Should use absolute path to root
      (is (= [:a {:href "/"} "Forsiden"] result))))

  (testing ":eden/link with {:nav :root}"
    (let [template [:eden/link {:nav :root}
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [:news :article :comments] ; deeply nested
                   :pages {:no {:landing {:slug ""
                                          :title "Forsiden"}}}}
          result (sg/process template context)]
      ;; Should link to root
      (is (= [:a {:href "/"} "Forsiden"] result))))

  (testing ":eden/link with explicit {:page-id ...}"
    (let [template [:eden/link {:content-key :about}
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [:news] ; current location doesn't matter
                   :pages {:no {:about {:slug "om-oss"
                                        :title "Om oss"}}}}
          result (sg/process template context)]
      ;; Should link to the specified page
      (is (= [:a {:href "/om-oss"} "Om oss"] result))))

  (testing ":eden/link with {:lang ...} for language switching"
    (let [template [:eden/link {:lang :en}
                    [:a {:href [:eden/get :link/href]} "English"]]
          context {:lang :no
                   :path [:products] ; on products page
                   :pages {:no {:products {:slug "produkter"
                                           :title "Produkter"}}
                           :en {:products {:slug "products"
                                           :title "Products"}}}
                   :site-config {:lang {:en {} :no {}}}} ; Configure languages
          result (sg/process template context)]
      ;; Should link to current page in English
      (is (= [:a {:href "/en/products"} "English"] result))))

  (testing ":eden/link with {:lang :no} from English page"
    (let [template [:eden/link {:lang :no}
                    [:a {:href [:eden/get :link/href]} "Norsk"]]
          context {:lang :en
                   :path [:products] ; on English products page
                   :pages {:no {:products {:slug "produkter"
                                           :title "Produkter"}}
                           :en {:products {:slug "products"
                                           :title "Products"}}}
                   :site-config {:lang {:en {} :no {}}}}
          result (sg/process template context)]
      ;; Should link to current page in Norwegian (no prefix)
      (is (= [:a {:href "/produkter"} "Norsk"] result))))

  (testing ":eden/link language switch on home page"
    (let [template [:eden/link {:lang :en}
                    [:a {:href [:eden/get :link/href]} "English"]]
          context {:lang :no
                   :path [:landing] ; on home page
                   :pages {:no {:landing {:slug ""
                                          :title "Forsiden"}}
                           :en {:landing {:slug ""
                                          :title "Home"}}}
                   :site-config {:lang {:en {} :no {}}}}
          result (sg/process template context)]
      ;; Should link to English home
      (is (= [:a {:href "/en/"} "English"] result))))

  (testing ":eden/link with both page-id and lang"
    (let [template [:eden/link {:content-key :about :lang :en}
                    [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
          context {:lang :no
                   :path [:products] ; current page doesn't matter
                   :pages {:en {:about {:slug "about"
                                        :title "About Us"}}}
                   :site-config {:lang {:en {} :no {}}}}
          result (sg/process template context)]
      ;; Should link to specific page in specific language
      (is (= [:a {:href "/en/about"} "About Us"] result))))

  (testing ":eden/link language switch preserves current page when no page-id"
    (let [template [:div.language-menu
                    [:eden/link {:lang :no}
                     [:a {:href [:eden/get :link/href]} "Norsk"]]
                    [:eden/link {:lang :en}
                     [:a {:href [:eden/get :link/href]} "English"]]]
          context {:lang :en
                   :path [:contact] ; on English contact page
                   :pages {:no {:contact {:slug "kontakt"
                                          :title "Kontakt"}}
                           :en {:contact {:slug "kontakt"
                                          :title "Kontakt"}}}
                   :site-config {:lang {:en {} :no {}}}}
          result (sg/process template context)]
      ;; Should create language switcher for current page
      (is (= [:div.language-menu
              [:a {:href "/kontakt"} "Norsk"]
              [:a {:href "/en/kontakt"} "English"]] result)))))

(deftest test-eden-get-in
  (testing ":eden/get-in basic nested access"
    (let [template [:div [:eden/get-in [:user :name]]]
          content {:data {:user {:name "John" :age 30}}}
          expected [:div "John"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in with deeper nesting"
    (let [template [:div [:eden/get-in [:user :address :city]]]
          content {:data {:user {:name "John"
                                 :address {:street "Main St"
                                           :city "Oslo"}}}}
          expected [:div "Oslo"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in with missing path returns path as string"
    (let [template [:div [:eden/get-in [:user :address :postal]]]
          content {:data {:user {:address {:city "Oslo"}}}}
          expected [:div "user.address.postal"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in with default value"
    (let [template [:div [:eden/get-in [:user :phone] "N/A"]]
          content {:data {:user {:name "John"}}}
          expected [:div "N/A"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in with nil in path"
    (let [template [:div [:eden/get-in [:user :contact :phone]]]
          content {:data {:user {:name "John"}}}
          expected [:div "user.contact.phone"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in with array index access"
    (let [template [:div [:eden/get-in [:items 0 :title]]]
          content {:data {:items [{:title "First"} {:title "Second"}]}}
          expected [:div "First"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in in attributes"
    (let [template [:a {:href [:eden/get-in [:nav :about :url]]} "Link"]
          content {:data {:nav {:about {:url "/about"}}}}
          expected [:a {:href "/about"} "Link"]]
      (is (= expected (sg/process template content)))))

  (testing ":eden/get-in with empty path returns full context"
    (let [template [:div [:eden/get-in []]]
          content {:data {:foo "bar"}}
          result (sg/process template content)]
      ;; The result will include :warn! in the context, filter it out for comparison
      (is (= [:div {:foo "bar"}]
             (update result 1 dissoc :warn!)))))

  (testing ":eden/get-in with single key path (same as :eden/get)"
    (let [template [:div [:eden/get-in [:key]]]
          content {:data {:key "value"}}
          expected [:div "value"]]
      (is (= expected (sg/process template content))))))

(deftest test-eden-with
  (testing "Basic :eden/with merges map into context"
    (let [template [:div
                    [:h1 [:eden/get :hello]]
                    [:eden/with :my-key
                     [:h2 [:eden/get :hello]]]]
          context {:data {:hello "one"
                          :my-key {:hello "two"}}}
          expected [:div
                    [:h1 "one"]
                    [:h2 "two"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/with with nested maps"
    (let [template [:eden/with :products
                    [:div
                     [:h3 [:eden/get-in [:logistics :title]]]
                     [:eden/with :logistics
                      [:p [:eden/get :title]]]]]
          context {:data {:products {:logistics {:title "Logistics Title"}
                                     :fish-health {:title "Fish Title"}}}}
          expected [:div
                    [:h3 "Logistics Title"]
                    [:p "Logistics Title"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/with with non-map value (should not merge)"
    (let [template [:eden/with :my-string
                    [:div [:eden/get :hello]]]
          context {:data {:hello "original"
                          :my-string "just a string"}}
          expected [:div "original"]]
      (is (= expected (sg/process template context))
          "Non-map values should not affect context")))

  (testing ":eden/with with missing key"
    (let [template [:eden/with :missing
                    [:div [:eden/get :hello]]]
          context {:data {:hello "original"}}
          expected [:div "original"]]
      (is (= expected (sg/process template context))
          "Missing key should not affect context")))

  (testing ":eden/with preserves outer context"
    (let [template [:div
                    [:eden/with :inner
                     [:p [:eden/get :a] " " [:eden/get :b]]]
                    [:p [:eden/get :a] " " [:eden/get :b]]]
          context {:data {:a "outer-a"
                          :b "outer-b"
                          :inner {:a "inner-a"}}}
          expected [:div
                    [:p "inner-a" " " "outer-b"] ; inner overrides :a, but :b comes from outer
                    [:p "outer-a" " " "outer-b"]]] ; back to outer context
      (is (= expected (sg/process template context)))))

  (testing ":eden/with with :eden/each inside"
    (let [template [:eden/with :products
                    [:eden/with :logistics
                     [:div
                      [:h4 [:eden/get :title]]
                      [:eden/each :items
                       [:span [:eden/get :name]]]]]]
          context {:data {:products {:logistics {:title "Logistics"
                                                 :items [{:name "Item1"}
                                                         {:name "Item2"}]}}}}
          expected [:div
                    [:h4 "Logistics"]
                    [:span "Item1"]
                    [:span "Item2"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/with with :eden/link for footer use case"
    (let [template [:eden/with :products
                    [:eden/with :logistics
                     [:div.footer-section
                      [:h4 [:eden/get :title]]
                      [:eden/link :products
                       [:eden/each :items
                        [:a {:href [:eden/get :link/href]} [:eden/get :name]]]]]]]
          context {:data {:products {:logistics {:title "Anteo Logistikk"
                                                 :items [{:name "Kartverktøy"}
                                                         {:name "Logifish"}]}}}
                   :pages {:no {:products {:slug "produkter" :title "Produkter"}}}
                   :lang :no}
          expected [:div.footer-section
                    [:h4 "Anteo Logistikk"]
                    [:a {:href "/produkter"} "Kartverktøy"]
                    [:a {:href "/produkter"} "Logifish"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/with with multiple levels of nesting"
    (let [template [:eden/with :level1
                    [:div
                     [:eden/get :a]
                     [:eden/with :level2
                      [:div
                       [:eden/get :a]
                       [:eden/with :level3
                        [:div [:eden/get :a]]]]]]]
          context {:data {:level1 {:a "L1"
                                   :level2 {:a "L2"
                                            :level3 {:a "L3"}}}}}
          expected [:div
                    "L1"
                    [:div
                     "L2"
                     [:div "L3"]]]]
      (is (= expected (sg/process template context))))))

(deftest test-eden-link-with-sections
  (testing "Link to section on current page"
    (let [context {:pages {:no {:products {:slug "produkter"}}}
                   :current-page-id :products
                   :sections {:logifish {:section-id "logifish"
                                         :parent-template :products}}
                   :lang :no}
          template [:eden/link :logifish
                    [:a {:href [:eden/get :link/href]} "See Logifish"]]
          result (sg/process template context)]
      (is (= [:a {:href "#logifish"} "See Logifish"] result))))

  (testing "Link to section on different page"
    (let [context {:pages {:no {:products {:slug "produkter"}
                                :about {:slug "om-oss"}}}
                   :current-page-id :about
                   :sections {:logifish {:section-id "logifish"
                                         :parent-template :products}}
                   :lang :no}
          template [:eden/link :logifish
                    [:a {:href [:eden/get :link/href]} "See Logifish"]]
          result (sg/process template context)]
      (is (= [:a {:href "/produkter#logifish"} "See Logifish"] result))))

  (testing "Link to standalone page (no section)"
    (let [context {:pages {:no {:products {:slug "produkter"}
                                :logifish {:slug "logifish"}}}
                   :current-page-id :products
                   :sections {} ; No sections registered
                   :lang :no}
          template [:eden/link :logifish
                    [:a {:href [:eden/get :link/href]} "Logifish Page"]]
          result (sg/process template context)]
      (is (= [:a {:href "/logifish"} "Logifish Page"] result))))

  (testing "Link resolution priority: standalone page over section with warning"
    (let [context {:pages {:no {:products {:slug "produkter"}
                                :logifish {:slug "logifish-page"}}} ; Standalone page exists
                   :current-page-id :products
                   :sections {:logifish {:section-id "logifish" ; Also a section
                                         :parent-template :products}}
                   :lang :no}
          template [:eden/link :logifish
                    [:a {:href [:eden/get :link/href]} "Logifish"]]
          result (sg/process template context)]
      ;; Should return map with result and warnings
      (is (map? result) "Should return map when warnings collected")
      ;; Should prefer standalone page over section
      (is (= [:a {:href "/logifish-page"} "Logifish"] (:result result)))
      ;; Should generate ambiguity warning
      (is (= 1 (count (:warnings result))))
      (is (= :ambiguous-link (-> result :warnings first :type)))
      (is (= :logifish (-> result :warnings first :link-id))))))

(deftest test-eden-link-preserves-context
  (testing ":eden/link preserves parent context for nested directives"
    (let [template [:eden/link :logistics
                    [:div
                     [:h2 [:eden/get :link/title]]
                     [:p [:eden/get-in [:products :logistics :description]]]]]
          content {:data {:products {:logistics {:description "Logistics description"}
                                     :fish-health {:description "Fish description"}}}
                   :pages {:no {:logistics {:slug "logistikk" :title "Anteo Logistikk"}}}
                   :lang :no}
          expected [:div
                    [:h2 "Anteo Logistikk"]
                    [:p "Logistics description"]]
          result (sg/process template content)]
      (is (= expected result) "sg/link should preserve parent context while adding link data")))

  (testing ":eden/link with missing parent data"
    (let [template [:eden/link :about
                    [:div [:eden/get-in [:missing :data]]]]
          content {:pages {:no {:about {:slug "om-oss" :title "Om oss"}}}
                   :lang :no}
          expected [:div "missing.data"]
          result (sg/process template content)]
      (is (= expected result) "Should handle missing data gracefully"))))

(deftest test-expand-directive
  (testing "expand-directive for :eden/link with page-id"
    (testing "keyword shorthand expands to map format"
      (let [elem [:eden/link :about [:div "About Us"]]
            result (sg/expand-directive elem {})]
        (is (map? result))
        (is (= :link (:eden/expanded result)))
        (is (= #{:about} (:eden/references result)))
        (is (= {:content-key :about} (:expanded-arg result)))
        (is (= [[:div "About Us"]] (:body result)))))

    (testing "explicit page-id expands to map format"
      (let [elem [:eden/link {:content-key :products.logistics} [:span "Products"]]
            result (sg/expand-directive elem {})]
        (is (map? result))
        (is (= #{:products.logistics} (:eden/references result)))
        (is (= {:content-key :products.logistics} (:expanded-arg result)))))

    (testing "page-id with language override expands correctly"
      (let [elem [:eden/link {:content-key :about :lang :en} [:a "English About"]]
            result (sg/expand-directive elem {})]
        (is (= #{:about} (:eden/references result)))
        (is (= {:content-key :about :lang :en} (:expanded-arg result))))))

  (testing "expand-directive for :eden/link without page-id"
    (testing "navigation links expand to map format without references"
      (let [elem [:eden/link {:nav :parent} [:a "Back"]]
            result (sg/expand-directive elem {})]
        (is (map? result))
        (is (= :link (:eden/expanded result)))
        (is (nil? (:eden/references result))) ; No references key
        (is (= {:nav :parent} (:expanded-arg result)))
        (is (= [[:a "Back"]] (:body result)))))

    (testing "language-only links expand to map format without references"
      (let [elem [:eden/link {:lang :en} [:a "English"]]
            result (sg/expand-directive elem {})]
        (is (map? result))
        (is (= :link (:eden/expanded result)))
        (is (nil? (:eden/references result))) ; No references key
        (is (= {:lang :en} (:expanded-arg result)))
        (is (= [[:a "English"]] (:body result))))))

  (testing "expand-directive for other directives"
    (testing "other sg directives pass through unchanged"
      (doseq [elem [[:eden/get :title]
                    [:eden/each :products [:div [:eden/get :name]]]
                    [:eden/include :hero {:title "Welcome"}]
                    [:eden/if :show [:div "Content"]]
                    [:eden/body]]]
        (is (= elem (sg/expand-directive elem {})))))

    (testing "regular hiccup passes through unchanged"
      (let [elem [:div.container [:h1 "Title"] [:p "Content"]]]
        (is (= elem (sg/expand-directive elem {}))))))

  (testing "already expanded elements"
    (testing "expanded map format passes through unchanged"
      (let [elem {:eden/expanded :link
                  :eden/references #{:about}
                  :expanded-arg {:content-key :about}
                  :body [[:div "About"]]}]
        (is (= elem (sg/expand-directive elem {})))))

    (testing "expanded map without references passes through unchanged"
      (let [elem {:eden/expanded :link
                  :expanded-arg {:nav :parent}
                  :body [[:a "Back"]]}]
        (is (= elem (sg/expand-directive elem {})))))))

(deftest test-missing-page-warnings
  (testing ":eden/link collects missing page warnings"
    (let [template [:eden/link :non-existent-page
                    [:a {:href [:eden/get :link/href]}
                     [:eden/get :link/title]]]
          content {:pages {:no {:about {:slug "om-oss" :title "Om oss"}}}
                   :lang :no}
          result (sg/process template content)]
      ;; Should return result and warnings
      (is (map? result))
      (is (contains? result :result))
      (is (contains? result :warnings))
      ;; Should still render with placeholder
      (is (= [:a {:href "#"} "non-existent-page"] (:result result)))
      ;; Should collect warning
      (is (= 1 (count (:warnings result))))
      (is (= :missing-page (-> result :warnings first :type)))
      (is (= :non-existent-page (-> result :warnings first :content-key)))))

  (testing "Multiple missing pages are collected"
    (let [template [:div
                    [:eden/link :missing-1 [:a {:href [:eden/get :link/href]} "Link 1"]]
                    [:eden/link :missing-2 [:a {:href [:eden/get :link/href]} "Link 2"]]]
          content {:pages {}}
          result (sg/process template content)]
      (is (= 2 (count (:warnings result))))
      (is (= #{:missing-1 :missing-2}
             (set (map :content-key (:warnings result)))))))

  (testing "Found pages don't generate warnings"
    (let [template [:eden/link :about [:a {:href [:eden/get :link/href]} "About"]]
          content {:pages {:no {:about {:slug "om-oss" :title "Om oss"}}}
                   :lang :no}
          result (sg/process template content)]
      ;; When no warnings, process returns the result directly
      (is (vector? result))
      (is (not (contains? result :warnings))))))

(deftest test-eden-link-missing-page-still-processes-body
  (testing ":eden/link with missing page still processes body with parent context"
    (let [template [:eden/link :non-existent
                    [:div
                     [:h2 [:eden/get :link/title]]
                     [:p [:eden/get-in [:products :logistics :description]]]]]
          content {:data {:products {:logistics {:description "This should appear!"}
                                     :fish-health {:description "So should this!"}}}
                   :pages {}} ; No pages defined
          result (sg/process template content)]
      ;; Process returns map when warnings collected
      (is (map? result))
      ;; Should render with placeholder link data but still process body
      (is (= [:div
              [:h2 "non-existent"] ; Placeholder title
              [:p "This should appear!"]] ; Parent context preserved!
             (:result result))
          "Body should still be processed with parent context even when page is missing")
      ;; Should also collect warning
      (is (= 1 (count (:warnings result))))
      (is (= :missing-page (-> result :warnings first :type)))))

  (testing ":eden/link with missing page in landing template scenario"
    (let [template [:eden/link :products.logistics
                    [:section.product-section
                     [:div.container
                      [:div.product-wrap
                       [:h2 [:eden/get :link/title]]
                       [:p [:eden/get-in [:products :logistics :description]]]
                       [:a {:href [:eden/get :link/href]} "Read more"]]]]]
          content {:data {:products {:logistics {:description "Logistics product description"}
                                     :fish-health {:description "Fish health description"}}}
                   :pages {}} ; Missing pages
          result (sg/process template content)]
      ;; Should render the section with placeholder link but real description
      (is (map? result) "Should return wrapped format with warnings")
      (is (= [:section.product-section
              [:div.container
               [:div.product-wrap
                [:h2 "products.logistics"] ; Placeholder title
                [:p "Logistics product description"] ; Real description from context!
                [:a {:href "#"} "Read more"]]]] ; Placeholder href
             (:result result))
          "Complex template should render with placeholders but preserve context data")
      (is (= 1 (count (:warnings result))) "Should have warning for missing page")
      (is (= :missing-page (-> result :warnings first :type))))))

(deftest test-eden-link-dynamic-page-id
  (testing "eden/link with dynamic page-id from eden/each context"
    (let [;; Context with content-data and page->url function
          context {:content-data {:news.article1 {:type "news"
                                                  :title "Article 1"
                                                  :slug "news/article-1"}
                                  :news.article2 {:type "news"
                                                  :title "Article 2"
                                                  :slug "news/article-2"}}
                   :pages {:no {:news.article1 {:slug "news/article-1"
                                                :title "Article 1"}
                                :news.article2 {:slug "news/article-2"
                                                :title "Article 2"}}}
                   :page->url (fn [{:keys [slug]}] (str "/" slug))
                   :lang :no}
          ;; Template that uses dynamic page-id
          template [:div
                    [:eden/each :eden/all :where {:type "news"}
                     [:article
                      ;; This now works - :eden/link processes [:eden/get :page-id]
                      [:eden/link [:eden/get :content-key]
                       [:a {:href [:eden/get :link/href]}
                        [:eden/get :title]]]]]]
          result (sg/process template context)]
      (is (= [:div
              [:article [:a {:href "/news/article-1"} "Article 1"]]
              [:article [:a {:href "/news/article-2"} "Article 2"]]]
             result)
          "Should be able to use dynamic page-id in eden/link")))

  (testing "eden/link with map syntax doesn't support dynamic values"
    ;; This test documents the limitation - map values are NOT processed
    (let [context {:pages {:en {:about {:title "About Us"
                                        :slug "about"}}}
                   :page->url (fn [{:keys [slug]}] (str "/" slug))
                   :current-page-id :about}
          ;; This syntax doesn't work - inner :eden/get is not evaluated
          template [:eden/link {:content-key [:eden/get :current-page-id]}
                    [:a {:href [:eden/get :link/href]}
                     "Go to " [:eden/get :link/title]]]
          result (sg/process-element template context)]
      ;; The map is returned as-is, so page-id lookup fails
      (is (= [:a {:href "#"} "Go to " "{:content-key [:eden/get :current-page-id]}"]
             result)
          "Map values are not recursively processed - this is a known limitation"))))

(deftest test-render-stack-helpers
  (testing "push-render-context builds stack correctly"
    (let [ctx {}
          ctx1 (sg/push-render-context ctx :content :landing)
          ctx2 (sg/push-render-context ctx1 :template :page)
          ctx3 (sg/push-render-context ctx2 :include :footer)]
      (is (= [[:content :landing]
              [:template :page]
              [:include :footer]]
             (sg/get-render-stack ctx3)))))

  (testing "get-render-stack returns empty vector for missing stack"
    (is (= [] (sg/get-render-stack {}))))

  (testing "push-render-context works with existing context data"
    (let [ctx {:some-data "value"
               :render-stack [[:content :about]]}
          updated (sg/push-render-context ctx :template :page)]
      (is (= "value" (:some-data updated)))
      (is (= [[:content :about] [:template :page]]
             (sg/get-render-stack updated))))))

(deftest test-missing-page-warning-with-stack
  (testing "Missing page warnings include render stack"
    (let [context {:pages {} ; No pages, so link will fail
                   :render-stack [[:content :about]
                                  [:template :page]
                                  [:include :footer]]}
          result (sg/process [:eden/link :missing-page [:a "Link"]] context)]
      (is (map? result))
      (is (= 1 (count (:warnings result))))
      (is (= [[:content :about]
              [:template :page]
              [:include :footer]]
             (-> result :warnings first :render-stack)))))

  (testing "Warnings work without render stack"
    (let [context {:pages {}} ; No render-stack
          result (sg/process [:eden/link :missing [:a "Link"]] context)]
      (is (map? result))
      (is (= 1 (count (:warnings result))))
      (is (empty? (-> result :warnings first :render-stack))))))

(deftest test-eden-render-template-from-data
  (testing ":eden/render uses template specified in content data"
    (let [template [:eden/render :footer]
          templates {:footer-template [:div.footer-special [:h4 [:eden/get :title]]]
                     :footer [:div.footer-default [:h4 [:eden/get :title]]]}
          content-data {:footer {:template :footer-template
                                 :title "My Footer"}}
          context {:templates templates
                   :content-data content-data}
          expected [:div.footer-special [:h4 "My Footer"]]]
      (is (= expected (sg/process template context))
          "Should use :footer-template from content data, not :footer")))

  (testing ":eden/render defaults to data key name when no template specified"
    (let [template [:eden/render :footer]
          templates {:footer [:div.footer [:h4 [:eden/get :title]]]}
          content-data {:footer {:title "My Footer"}} ; No :template field
          context {:templates templates
                   :content-data content-data}
          expected [:div.footer [:h4 "My Footer"]]]
      (is (= expected (sg/process template context))
          "Should default to :footer template when content has no :template field"))))

(deftest test-eden-render-with-section-id
  (testing ":eden/render adds section ID to rendered element"
    (let [template [:eden/render {:data :logifish :section-id "logifish"}]
          templates {:logifish [:div.product [:h2 "Logifish"]]}
          content-data {:logifish {:title "My Product"}}
          context {:templates templates :content-data content-data}
          result (sg/process template context)]
      ;; Should add id="logifish" to the div
      (is (= [:div.product {:id "logifish"} [:h2 "Logifish"]] result))))

  (testing ":eden/render with section ID on element with existing attrs"
    (let [template [:eden/render {:data :product :section-id "product-section"}]
          templates {:product [:div {:class "product"} [:h2 "Product"]]}
          content-data {:product {}}
          context {:templates templates :content-data content-data}
          result (sg/process template context)]
      ;; Should merge id into existing attrs
      (is (= [:div {:class "product" :id "product-section"} [:h2 "Product"]] result))))

  (testing ":eden/render with section ID wraps non-hiccup content"
    (let [template [:eden/render {:data :text :section-id "text-section"}]
          templates {:text "Just plain text"}
          content-data {:text {}}
          context {:templates templates :content-data content-data}
          result (sg/process template context)]
      ;; Should wrap in div with id
      (is (= [:div {:id "text-section"} "Just plain text"] result))))

  (testing ":eden/render without section ID unchanged"
    (let [template [:eden/render :product]
          templates {:product [:div.product [:h2 "Product"]]}
          content-data {:product {}}
          context {:templates templates :content-data content-data}
          result (sg/process template context)]
      ;; Should not add any id
      (is (= [:div.product [:h2 "Product"]] result)))))

(deftest test-eden-render
  (testing ":eden/render with simple keyword expands to map"
    (let [template [:eden/render :footer]
          templates {:footer [:div.footer [:h4 [:eden/get :title]]]}
          content-data {:footer {:title "My Footer"}}
          context {:templates templates
                   :content-data content-data}
          expected [:div.footer [:h4 "My Footer"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/render with explicit template and data"
    (let [template [:eden/render {:template :card :data :product}]
          templates {:card [:div.card [:h3 [:eden/get :name]] [:p [:eden/get :price]]]}
          content-data {:product {:name "Widget" :price "$99"}}
          context {:templates templates
                   :content-data content-data}
          expected [:div.card [:h3 "Widget"] [:p "$99"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/render with missing template returns error placeholder"
    (let [template [:eden/render :nonexistent]
          context {:templates {} :content-data {}}
          result (sg/process template context)]
      (is (map? result) "Should return map with :result and :warnings")
      (is (vector? (:result result)) "Should return error placeholder element")
      (is (= :div.eden-render-error (first (:result result)))
          "Should return error div when template missing")
      (is (= "missing-template" (get-in (:result result) [1 :data-error]))
          "Error div should have data-error attribute")
      (is (str/includes? (last (:result result)) "Missing template")
          "Error message should mention missing template")
      (is (= 1 (count (:warnings result)))
          "Should collect warning for missing template")
      (is (= :missing-template (-> result :warnings first :type)))
      (is (= :nonexistent (-> result :warnings first :template-id)))))

  (testing ":eden/render with missing data processes with empty context"
    (let [template [:eden/render :footer]
          templates {:footer [:div [:eden/get :title]]}
          context {:templates templates :content-data {}}
          result (sg/process template context)]
      ;; :eden/get will generate a warning for missing key
      (is (map? result) "Should return map with warnings")
      (is (= [:div [:span.missing-content "[:eden/get :title]"]] (:result result))
          "Should process template with empty context when data missing - :eden/get returns visible indicator")
      (is (= 1 (count (:warnings result))) "Should have warning for missing key")
      (is (= :missing-key (-> result :warnings first :type)))))

  (testing ":eden/render preserves parent context for system keys"
    (let [template [:eden/render :footer]
          templates {:footer [:div [:eden/get :title] " © " [:eden/get :eden/current-year]]}
          content-data {:footer {:title "My Site"}}
          context {:templates templates
                   :content-data content-data
                   :build-constants {:eden/current-year "2025"}
                   :pages {:about {:slug "about"}}
                   :strings {:copyright "©"}}
          expected [:div "My Site" " © " "2025"]]
      (is (= expected (sg/process template context))
          "Should preserve system keys like :eden/current-year from parent context")))

  (testing ":eden/render with nested directives"
    (let [template [:eden/render :product-list]
          templates {:product-list [:ul [:eden/each :products [:li [:eden/get :name]]]]}
          content-data {:product-list {:products [{:name "A"} {:name "B"}]}}
          context {:templates templates :content-data content-data}
          expected [:ul [:li "A"] [:li "B"]]]
      (is (= expected (sg/process template context)))))

  (testing ":eden/render within :eden/each"
    (let [template [:eden/each :sections [:eden/render [:eden/get :template-id]]]
          templates {:header [:h1 [:eden/get :text]]
                     :footer [:div [:eden/get :text]]}
          content-data {:header {:text "Header Text"}
                        :footer {:text "Footer Text"}}
          context {:data {:sections [{:template-id :header} {:template-id :footer}]}
                   :templates templates
                   :content-data content-data}
          expected [[:h1 "Header Text"] [:div "Footer Text"]]]
      (is (= expected (sg/process template context))
          "Should support dynamic template selection within loops"))))

(deftest test-eden-render-error-handling
  (testing ":eden/render with invalid render spec returns error placeholder"
    (let [template [:eden/render {:data nil :template nil}]
          context {:templates {:page [:div "Page"]}}
          result (sg/process template context)]
      (is (map? result) "Should return map with result and warnings")
      (is (= :div.eden-render-error (first (:result result)))
          "Should return error div for invalid spec")
      (is (= "invalid-render-spec" (get-in (:result result) [1 :data-error])))
      (is (= 1 (count (:warnings result))))
      (is (= :invalid-render-spec (-> result :warnings first :type)))))

  (testing ":eden/render with non-keyword template returns error"
    (let [template [:eden/render {:data :test :template "string-template"}]
          context {:templates {:page [:div "Page"]}
                   :content-data {:test {}}}
          result (sg/process template context)]
      (is (= :div.eden-render-error (first (:result result)))
          "Should return error for non-keyword template")
      (is (= "invalid-render-spec" (get-in (:result result) [1 :data-error])))))

  (testing ":eden/render map form with [:eden/get] directives"
    (let [template [:eden/render {:data [:eden/get :content-key]
                                  :template [:eden/get :template]}]
          context {:templates {:page [:div [:h1 [:eden/get :title]]]}
                   :content-data {:products.test {:title "Test Product"}}
                   :data {:content-key :products.test
                          :template :page}}
          result (sg/process template context)]
      (is (= [:div [:h1 "Test Product"]] result)
          "Should process [:eden/get] directives in map form")))

  (testing ":eden/render with section-id from [:eden/get]"
    (let [template [:eden/render {:data :product
                                  :section-id [:eden/get :slug]}]
          templates {:product [:div "Product content"]}
          context {:templates templates
                   :content-data {:product {}}
                   :data {:slug "my-product"}}
          result (sg/process template context)]
      (is (= [:div {:id "my-product"} "Product content"] result)
          "Should process [:eden/get] in section-id field"))))

(deftest test-include-with-render-stack
  (testing ":eden/include adds to render stack"
    (let [templates {:nav [:div [:eden/link :nonexistent [:a "Bad"]]]}
          context {:render-stack [[:content :landing] [:template :page]]
                   :templates templates
                   :pages {}}
          result (sg/process [:eden/include :nav] context)]
      (is (map? result))
      (let [warning (first (:warnings result))]
        (is (= :nonexistent (:content-key warning)))
        ;; The stack should include the :nav include
        (is (= [[:content :landing]
                [:template :page]
                [:include :nav]]
               (:render-stack warning))))))

  (testing "Nested includes build up the stack"
    (let [templates {:header [:div [:eden/include :nav]]
                     :nav [:eden/link :missing [:a "Link"]]}
          context {:render-stack [[:content :home]]
                   :templates templates
                   :pages {}}
          result (sg/process [:eden/include :header] context)]
      (is (map? result))
      (let [warning (first (:warnings result))]
        (is (= [[:content :home]
                [:include :header]
                [:include :nav]]
               (:render-stack warning)))))))
