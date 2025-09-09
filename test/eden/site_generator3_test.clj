(ns eden.site-generator3-test
  (:require [clojure.test :refer [deftest is testing] :as t]
            [eden.site-generator3 :as sg]))


(deftest process
  (testing "basic"
    (is (nil? (sg/process nil nil))))

  (testing "hiccup is unchanged"
    (let [hiccup [:h1 "hello world"]]
      (is (= hiccup (sg/process hiccup nil)))))

  (testing "hiccup is unchanged - attrs"
    (let [attribs [:div {:foo 1} "attribs"]]
      (is (= attribs (sg/process attribs nil)))))


  (testing "vector processings"
    (let [vec-of-hiccup [[:h2 "hello"]
                         [:h1 "world"]]]
      (is (= vec-of-hiccup (sg/process vec-of-hiccup nil))))))


(deftest eden-get
  (testing "basic"
    (is (= 3 (sg/process [:eden/get :foo] {:data {:foo 3}}))))

  (testing "nesting"
    (is (= 3 (sg/process [:eden/get [:eden/get :the-key]]
                         {:data {:value 3
                                 :the-key :value}}))))
  (testing "default-value"
    (is (= 4 (sg/process [:eden/get :missing 4]
                         {:data {:other :key}}))))

  (testing "default-value evaluated"
    (is (= :key (sg/process [:eden/get :missing [:eden/get :other]]
                            {:data {:other :key}}))))

  (testing "raw html"
    (is (= [:span {:innerHTML "<p>hello</p>"}]
           (sg/process [:eden/get :html/content]
                       {:data {:html/content "<p>hello</p>"}})))))

(deftest eden-if
  (testing "branches"
    (is (= [:h2 "TRUE"] (sg/process [:eden/if true [:h2 "TRUE"] [:h2 "FALSE"]] nil)))
    (is (= [:h2 "FALSE"] (sg/process [:eden/if false [:h2 "TRUE"] [:h2 "FALSE"]] nil))))
  (testing "expression support"
    (is (true? (sg/process [:eden/if [:= 2 2] true] nil)))
    (is (nil? (sg/process [:eden/if [:= 2 3] true] nil)))
    (is (true? (sg/process [:eden/if [:< 2 3] true] nil)))
    (is (nil? (sg/process [:eden/if [:> 2 3] true] nil)))
    (is (false? (sg/process [:eden/if [:> 2 3] true false] nil)))))

(deftest eden-if-and-get
  (testing "branch on get result"
    (is (= [:h1 "is true"]
           (sg/process [:eden/if [:eden/get :truthy]
                        [:h1 "is true"]
                        [:h1 "is false"]]
                       {:data {:truthy 1}}))))

  (testing "with comparison"
    (is (= [:h1 "is true"]
           (sg/process [:eden/if [:> [:eden/get :value] 10]
                        [:h1 "is true"]
                        [:h1 "is false"]]
                       {:data {:value 11}})))

    (is (= [:h1 "is false"]
           (sg/process [:eden/if [:> [:eden/get :value] 10]
                        [:h1 "is true"]
                        [:h1 "is false"]]
                       {:data {:value 9}}))))
  )

(deftest eden-site-config
  (testing "basic"
    (is (= 55 (sg/process [:eden/site-config :val]
                          {:site-config {:val 55}}))))

  (testing "nested"
    (is (= {:hoho "haha"}
           (sg/process [:eden/site-config :some 0 :nested :value]
                       {:site-config
                        {:some [{:nested {:value {:hoho "haha"}}}]}})))))


(deftest eden-link
  (testing "page link, returns placeholder"
    (is (= [:div
            [:a {:href {:content-key :about, :type :eden.link.placeholder/href}}
             {:content-key :about, :type :eden.link.placeholder/title}]]

           (sg/process [:div
                        [:eden/link :about
                         [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]]
                       {}))))

  (testing "adds reference to linked page"
    (let [references (atom [])]
      (sg/process [:eden/link :about
                   [:a {:href [:eden/get :link/href]} [:eden/get :link/title]]]
                  {:add-reference! (fn [ref]
                                     (swap! references conj ref))})
      (is (= 1 (count @references)))
      (is (= :about (first @references)))))

  ;; TODO: lang
  ;; TODO: path
  )

(deftest eden-each
  (testing "simple list"
    (is (= [:ul [:li "foo"] [:li "bar"]]
           (sg/process [:ul
                        [:eden/each :items
                         [:li [:eden/get :name]]]]
                       {:data {:items [{:name "foo"}
                                       {:name "bar"}]}}))))

  (testing "multiple children spliced"
    (is (= [:div.collection
            [:h1 "One"]
            [:p "paragraph 1"]
            [:h1 "Two"]
            [:p "paragraph 2"]]
           (sg/process [:div.collection
                        [:eden/each :items
                         [:h1 [:eden/get :header]]
                         [:p [:eden/get :content]]]]
                       {:data {:items [{:header "One" :content "paragraph 1"}
                                       {:header "Two" :content "paragraph 2"}]}}))))

  (testing "with filtering and sorting"
    (is (= [:div
            [:article [:h2 "first"] [:p "Posted on" "2025-08-01"]]
            [:article [:h2 "third"] [:p "Posted on" "2025-08-03"]]]
           (sg/process [:div
                        [:eden/each :posts
                         :where {:published true}
                         :order-by [:date :asc]
                         :limit 2
                         [:article
                          [:h2 [:eden/get :title]]
                          [:p "Posted on" [:eden/get :date]]]]]
                       {:data {:posts [{:title "fourth" :date "2025-08-04" :published true}
                                       {:title "third" :date "2025-08-03" :published true}
                                       {:title "second" :date "2025-08-02" :published false}
                                       {:title "first" :date "2025-08-01" :published true}]}})))
    (testing "descending"
      (is (= [:div
              [:article [:h2 "fourth"] [:p "Posted on" "2025-08-04"]]
              [:article [:h2 "third"] [:p "Posted on" "2025-08-03"]]]
             (sg/process [:div
                          [:eden/each :posts
                           :where {:published true}
                           :order-by [:date :desc]
                           :limit 2
                           [:article
                            [:h2 [:eden/get :title]]
                            [:p "Posted on" [:eden/get :date]]]]]
                         {:data {:posts [{:title "fourth" :date "2025-08-04" :published true}
                                         {:title "third" :date "2025-08-03" :published true}
                                         {:title "second" :date "2025-08-02" :published false}
                                         {:title "first" :date "2025-08-01" :published true}]}})))))

  (testing "map iteration"
    (is (= [:dl [:dt :foo] [:dd 1] [:dt :bar] [:dd 2]]
           (sg/process [:dl
                        [:eden/each :metadata
                         [:dt [:eden/get :eden.each/key]]
                         [:dd [:eden/get :eden.each/value]]]]
                       {:data {:metadata {:foo 1 :bar 2}}}))))


  (testing "grouping"
    (is (= [[:section [:h2 "a"] [:article "1"] [:article "2"]] [:section [:h2 "b"] [:article "3"] [:article "4"]]]
           (sg/process [:eden/each :articles
                        :group-by :category
                        [:section
                         [:h2 [:eden/get :eden.each/group-key]]
                         [:eden/each :eden.each/group-items
                          [:article [:eden/get :title]]]]]
                       {:data {:articles [{:category "a" :title "1"}
                                          {:category "a" :title "2"}
                                          {:category "b" :title "3"}
                                          {:category "b" :title "4"}]}}))))

  (testing "grouping, explicit eden/get"
    (is (= [[:section [:h2 "a"] [:article "1"] [:article "2"]] [:section [:h2 "b"] [:article "3"] [:article "4"]]]
           (sg/process [:eden/each :articles
                        :group-by :category
                        [:section
                         [:h2 [:eden/get :eden.each/group-key]]
                         [:eden/each [:eden/get :eden.each/group-items]
                          [:article [:eden/get :title]]]]]
                       {:data {:articles [{:category "a" :title "1"}
                                          {:category "a" :title "2"}
                                          {:category "b" :title "3"}
                                          {:category "b" :title "4"}]}}))))

  (testing "grouping, sorted"
    (is (= [[:section [:h2 "a"] [:article "1"] [:article "2"]] [:section [:h2 "b"] [:article "3"] [:article "4"]]]
           (sg/process [:eden/each :articles
                        :group-by :category
                        :order-by :title
                        [:section
                         [:h2 [:eden/get :eden.each/group-key]]
                         [:eden/each :eden.each/group-items
                          [:article [:eden/get :title]]]]]
                       {:data {:articles [{:category "a" :title "2"}
                                          {:category "a" :title "1"}
                                          {:category "b" :title "4"}
                                          {:category "b" :title "3"}]}})))))

(deftest eden-render
  (testing "simple component"
    (is (= [:h2 "world"]
           (sg/process [:eden/render :sidebar]
                       {:content {:en {:sidebar {:hello "world"}}}
                        :lang :en
                        :templates {:sidebar [:h2 [:eden/get :hello]]}}))))


  (testing "simple component, content not found"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/render :this-page-is-not-found]"]
             (sg/process [:eden/render :this-page-is-not-found]
                         {:content {:en {:sidebar {:hello "world"}}}
                          :warn! #(swap! warnings conj %)
                          :lang :en
                          :templates {:sidebar [:h2 [:eden/get :hello]]}})))
      (is (= 1 (count @warnings)))
      (is (= [{:type :missing-page-content,
               :directive :eden/render
               :lang :en,
               :spec {:data :this-page-is-not-found}}]
             @warnings))))

  (testing "simple component, template not found"
    (let [warnings (atom [])]
      (is (= [:span.missing-content "[:eden/render :sidebar]"]
             (sg/process [:eden/render :sidebar]
                         {:content {:en {:sidebar {:hello "world"
                                                   :template :my-nonexistent-template}}}
                          :warn! #(swap! warnings conj %)
                          :lang :en
                          :templates {:sidebar [:h2 [:eden/get :hello]]}})))
      (is (= 1 (count @warnings)))
      (is (= [{:type :missing-render-template
               :directive :eden/render
               :lang :en
               :template :my-nonexistent-template
               :spec {:data :sidebar}}]
             @warnings))))

  (testing "simple component, page has template"
    (is (= [:p "I am foo"]
           (sg/process [:eden/render :sidebar]
                       {:content {:en {:sidebar {:hello "world"
                                                 :template :foo}}}
                        :lang :en
                        :templates {:sidebar [:h2 [:eden/get :hello]]
                                    :foo [:p "I am foo"]}}))))

  (testing "simple component, render has template"
    (is (= [:div "pang pang"]
           (sg/process [:eden/render {:data :sidebar
                                      :template :yoyo}]
                       {:content {:en {:sidebar {:hello "world"
                                                 :template :foo}}}
                        :lang :en
                        :templates {:sidebar [:h2 [:eden/get :hello]]
                                    :foo [:p "I am foo"]
                                    :yoyo [:div "pang pang"]}}))))

  (testing "simple component, language not set"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Language not set"
         (sg/process [:eden/render {:data :sidebar
                                    :template :yoyo}]
                     {:content {:en {:sidebar {:hello "world"
                                               :template :foo}}}
                      :templates {:sidebar [:h2 [:eden/get :hello]]
                                  :foo [:p "I am foo"]
                                  :yoyo [:div "pang pang"]}}))))

  (testing "simple component, adds section"
    (let [sections (atom {})]
      (sg/process [:eden/render {:data :sidebar
                                 :section-id :my-section}]
                  {:add-section! (fn [section-id parent]
                                   (swap! sections assoc section-id parent))
                   :lang :en
                   :content-key :the-parent
                   :content {:en {:sidebar {:hello "world" :template :foo}}}
                   :templates {:sidebar [:h2 [:eden/get :hello]]
                               :foo [:p "I am foo"]
                               :yoyo [:div "pang pang"]}})
      (is (= {:my-section {:parent :the-parent}} @sections)))))

(deftest eden-with
  (testing "merge product details"
    (is (= [[:div.product [:h2 "product name"] [:p.price 10.99] [:p "ok product"]]]
           (sg/process [:eden/with :product
                        [:div.product
                         [:h2 [:eden/get :name]]
                         [:p.price [:eden/get :price]]
                         [:p [:eden/get :description]]]]
                       {:data {:product {:name "product name"
                                         :price 10.99
                                         :description "ok product"}}}))))

  (testing "multiple children"
    (is (= [:div.product [:h2 "product name"] [:p.price 10.99] [:p "ok product"]]
           (sg/process [:div.product
                        [:eden/with :product
                         [:h2 [:eden/get :name]]
                         [:p.price [:eden/get :price]]
                         [:p [:eden/get :description]]]]
                       {:data {:product {:name "product name"
                                         :price 10.99
                                         :description "ok product"}}})))

    (is (= [[:h2 "product name"] [:p.price 10.99] [:p "ok product"]]
           (sg/process [:eden/with :product
                        [:h2 [:eden/get :name]]
                        [:p.price [:eden/get :price]]
                        [:p [:eden/get :description]]]
                       {:data {:product {:name "product name"
                                         :price 10.99
                                         :description "ok product"}}}))))

  (testing "nested with"
    (is (= [[:div.profile [:h1 "user name"] [:div.settings [:p "Theme: " "dark"] [:p "Language: " "braile"]]]]
           (sg/process [:eden/with :user
                        [:div.profile
                         [:h1 [:eden/get :name]]
                         [:eden/with :preferences
                          [:div.settings
                           [:p "Theme: " [:eden/get :theme]]
                           [:p "Language: " [:eden/get :language]]]]]]
                       {:data {:user {:name "user name"
                                      :preferences {:theme "dark"
                                                    :language "braile"}}}}))))

  (testing "missing key"
    (let [warnings (atom [])]
      (is (= [[:p [:span.missing-content "[:eden/get :value]"]]]
             (sg/process [:eden/with :foo
                          [:p [:eden/get :value]]]
                         {:warn! #(swap! warnings conj %)})))

      (let [relevant-warnings (filter #(= (:type %) :with-directive-data-not-found) @warnings)]
        (is (= 1 (count relevant-warnings)))
        (is (= {:type :with-directive-data-not-found,
                :data-key :foo
                :data nil}
               (first relevant-warnings)))))))

(deftest eden-include
  (testing "simple include"
    (is (= [:h1 "I am a header and so can you"]
           (sg/process [:eden/include :header]
                       {:templates {:header [:h1 "I am a header and so can you"]}}))))

  (testing "with context override"
    (is (= [:p "currently on" :home]
           (sg/process [:eden/include :nav {:active-page :home}]
                       {:data {:active-page :away}
                        :templates {:nav [:p "currently on" [:eden/get :active-page]]}})))))

(deftest eden-body
  (testing "inserts body"
    (is (= [:div [:h1 "my page"] [:p "I am the (processed) body"]]
           (sg/process [:div
                        [:h1 [:eden/get :title]]
                        [:eden/body]]
                       {:body [:p "I am the (processed) body"]
                        :data {:title "my page"}}))))

  (testing "warns when body missing"
    (let [warnings (atom [])]
      (is (= [:div [:h1 "my page"] nil]
             (sg/process [:div
                          [:h1 [:eden/get :title]]
                          [:eden/body]]
                         {:data {:title "my page"}
                          :content-key :my-page
                          :warn! #(swap! warnings conj %)})))
      (is (= [{:type :missing-body-in-context
               :directive :eden/body
               :content-key :my-page}]
             @warnings)))))

(deftest eden-get-in
  (testing "nested map access"
    (is (= "user name"
           (sg/process [:eden/get-in [:user :profile :name]]
                       {:data {:user {:profile {:name "user name"}}}}))))

  (testing "mixed map and vector access"
    (is (= "the title"
           (sg/process [:eden/get-in [:items 0 :title]]
                       {:data {:items [{:title "the title"}]}}))))

  (testing "empty path returns full data"
    (is (= {:a 1 :b 2}
           (sg/process [:eden/get-in []]
                       {:data {:a 1 :b 2}}))))

  (testing "single element-path (equivalent to :eden/get"
    (let [context {:data {:value 42}}]
      (is (= 42
             (sg/process [:eden/get-in [:value]] context)
             (sg/process [:eden/get :value] context))))))

(deftest missing-directive
  (testing "nonexistent"
    (is (= [:span.unknown-directive "[:eden/not-real-directive ...]"]
           (sg/process [:eden/not-real-directive :foo :bar] nil)))))


#_(t/run-tests)
