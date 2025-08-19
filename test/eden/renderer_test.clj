(ns eden.renderer-test
  (:require [clojure.test :refer [deftest is testing]]
            [eden.renderer :as renderer]))

(deftest test-expand-all-templates
  (testing "Expands all templates and caches the results"
    (let [ctx {:templates {:landing [:div [:eden/link :about [:a "About"]]]
                           :about [:div [:eden/include :footer]]
                           :footer [:footer [:eden/link :privacy [:a "Privacy"]]]}
               :content {:no {:landing {:title "Landing"}
                              :about {:title "About"}
                              :privacy {:title "Privacy"}}}}
          result (renderer/expand-all-templates ctx)]
      (is (contains? result :expanded-templates))
      (is (= (set (keys (:expanded-templates result)))
             #{:landing :about :footer}))
      ;; Check that references are collected
      (is (contains? (get-in result [:expanded-templates :landing :eden/references]) :about))
      (is (contains? (get-in result [:expanded-templates :footer :eden/references]) :privacy))))

  (testing "Handles templates without references"
    (let [ctx {:templates {:simple [:div [:h1 "Hello"]]}}
          result (renderer/expand-all-templates ctx)]
      (is (get-in result [:expanded-templates :simple]))
      (is (empty? (get-in result [:expanded-templates :simple :eden/references]))))))

(deftest test-collect-dependencies-and-pages
  (testing "Returns both visited templates and pages to render"
    (let [ctx {:expanded-templates {:landing {:eden/references #{:about}}
                                    :about {:eden/includes #{:nav}}
                                    :nav {:eden/references #{:products}}
                                    :products {:eden/references #{}}}
               :content {:no {:landing {:title "Landing"}
                              :about {:title "About"}
                              :products {:title "Products"}}}
               :config {:wrapper :base}
               :render-roots #{:landing}}
          {:keys [visited pages-to-render]} (renderer/collect-dependencies-and-pages ctx)]
      ;; All templates including includes should be visited
      (is (contains? visited :landing))
      (is (contains? visited :about))
      (is (contains? visited :nav))
      (is (contains? visited :products))
      ;; But nav should NOT be in pages-to-render
      (is (contains? pages-to-render :landing))
      (is (contains? pages-to-render :about))
      (is (contains? pages-to-render :products))
      (is (not (contains? pages-to-render :nav)))))

  (testing "Wrapper is always processed but never rendered"
    (let [ctx {:expanded-templates {:base {:eden/references #{:privacy}}
                                    :landing {:eden/references #{}}
                                    :privacy {:eden/references #{}}}
               :content {:no {:landing {:title "Landing"}
                              :privacy {:title "Privacy"}}}
               :config {:wrapper :base}
               :render-roots #{:landing}}
          {:keys [visited pages-to-render]} (renderer/collect-dependencies-and-pages ctx)]
      ;; Base should be visited
      (is (contains? visited :base))
      ;; Privacy should be rendered (referenced from base)
      (is (contains? pages-to-render :privacy))
      ;; But base itself should NOT be rendered
      (is (not (contains? pages-to-render :base)))))

  (testing "Includes referenced via :eden/include are processed but not rendered"
    (let [ctx {:expanded-templates {:landing {:eden/includes #{:nav}}
                                    :nav {:eden/references #{:about}}
                                    :about {:eden/references #{}}}
               :content {:no {:landing {:title "Landing"}
                              :about {:title "About"}}}
               :config {:wrapper :base}
               :render-roots #{:landing}}
          {:keys [visited pages-to-render]} (renderer/collect-dependencies-and-pages ctx)]
      ;; Nav should be visited (included by landing)
      (is (contains? visited :nav))
      ;; About should be rendered (linked from nav)
      (is (contains? pages-to-render :about))
      ;; But nav should NOT be rendered
      (is (not (contains? pages-to-render :nav))))))

(deftest test-render-page
  (testing "render-page processes content correctly"
    (let [;; Simulate what render-page receives
          page {:content {:template :test-template
                          :hero-title "Test Title"
                          :hero-subtitle "Test Subtitle"
                          :lang :no
                          :lang-prefix ""}
                :content-key :test
                :lang-code :no}

          ;; Templates - simplified versions
          templates {:test-template [[:h1 [:eden/get :hero-title]]
                                     [:h2 [:eden/get :hero-subtitle]]]
                     :wrapper [:html [:body [:eden/body]]]}

          config {:wrapper :wrapper}

          ;; Call render-page
          result (renderer/render-page {:config config :templates templates} page)]

      ;; Check the HTML contains our text
      (is (some? (:html result)))
      (is (string? (:html result)))
      (is (not (.contains (:html result) "hero-title"))) ; Should NOT contain the raw key
      (is (.contains (:html result) "Test Title")) ; Should contain the actual value
      (is (.contains (:html result) "Test Subtitle"))))

  (testing "render-page with missing content returns page with warning"
    (let [page {:content-key :test :lang-code :no}
          templates {}
          config {:wrapper :wrapper}
          result (renderer/render-page {:config config :templates templates} page)]
      (is (= :missing-content (-> result :warnings first :type)))
      (is (= :test (-> result :warnings first :content-key)))
      (is (= :no (-> result :warnings first :lang-code))))))

(deftest test-scan-for-sections
  (testing "Basic section scanning"
    (let [context {:expanded-templates
                   {:page {:body [:div
                                  [:h1 "Products"]
                                  [:eden/render {:data :logifish :section-id "logifish"}]
                                  [:eden/render {:data :fishctrl :section-id "fishctrl"}]]}}
                   :templates {:logifish [:div "Logifish content"]
                               :fishctrl [:div "FishCtrl content"]}}
          result (renderer/scan-for-sections context)]
      (is (contains? result :sections))
      (is (= {:logifish {:section-id "logifish" :parent-template :page :data :logifish}
              :fishctrl {:section-id "fishctrl" :parent-template :page :data :fishctrl}}
             (:sections result)))))

  (testing "Sections across multiple templates"
    (let [context {:expanded-templates
                   {:products {:body [:div
                                      [:eden/render {:data :logifish :section-id "logifish"}]]}
                    :about {:body [:div
                                   [:eden/render {:data :team :section-id "team-section"}]]}}
                   :templates {:logifish [:div "Logifish"]
                               :team [:div "Team"]}}
          result (renderer/scan-for-sections context)]
      (is (= {:logifish {:section-id "logifish" :parent-template :products :data :logifish}
              :team {:section-id "team-section" :parent-template :about :data :team}}
             (:sections result)))))

  (testing "No sections case"
    (let [context {:expanded-templates
                   {:page {:body [:div [:h1 "No sections here"]]}}}
          result (renderer/scan-for-sections context)]
      (is (= {} (:sections result)))))

  (testing "Section with template key instead of data"
    (let [context {:expanded-templates
                   {:page {:body [:eden/render {:template :card :section-id "product-card"}]}}}
          result (renderer/scan-for-sections context)]
      (is (= {:card {:section-id "product-card" :parent-template :page :data :card}}
             (:sections result)))))

  (testing "Mixed render directives - only captures those with section-id"
    (let [context {:expanded-templates
                   {:page {:body [:div
                                  [:eden/render :header] ; No section-id
                                  [:eden/render {:data :logifish :section-id "logifish"}]
                                  [:eden/render {:template :footer}] ; No section-id
                                  [:eden/render {:data :fishctrl :section-id "fishctrl"}]]}}}
          result (renderer/scan-for-sections context)]
      (is (= {:logifish {:section-id "logifish" :parent-template :page :data :logifish}
              :fishctrl {:section-id "fishctrl" :parent-template :page :data :fishctrl}}
             (:sections result))))))

(deftest test-render-page-missing-template
  (testing "render-page without matching template returns page with warning"
    (let [;; Page with no matching template and no default
          page {:content-key :orphan-page
                :lang-code :no
                :content {:title "Orphan Page"
                          :slug "orphan"
                          :template :nonexistent ; Template that doesn't exist
                          :content/html "<p>Some content</p>"}}
          templates {:landing [:div "Landing"]
                     :page [:div "Page"]
                     :base [:html [:body [:eden/body]]]}
          config {:wrapper :base}
          result (renderer/render-page {:config config
                                        :templates templates
                                        :pages-registry {}
                                        :strings {}
                                        :page->url identity}
                                       page)]
      ;; Should return warning data structure
      (is (= :missing-template (-> result :warnings first :type))
          "Should have missing-template warning")
      (is (= :nonexistent (-> result :warnings first :template-name))
          "Warning should reference the missing template")
      (is (= :orphan-page (-> result :warnings first :content-key))
          "Warning should reference the page")
      ;; Should return page without :html key
      (is (nil? (:html result))
          "Should not have :html when template is missing")
      ;; Should preserve original page data
      (is (= :orphan-page (:content-key result)))
      (is (= (:content page) (:content result)))))

  (testing "render-page with missing template for content-key"
    (let [page {:content-key :typed-page
                :lang-code :no
                :content {:title "Typed Page"
                          :slug "typed"
                          :content/html "<p>Article content</p>"}}
          templates {:page [:div [:h1 [:eden/get :title]] [:eden/get :content/html]]
                     :base [:html [:body [:eden/body]]]}
          config {:wrapper :base}
          result (renderer/render-page {:config config
                                        :templates templates
                                        :pages-registry {}
                                        :strings {}
                                        :page->url identity}
                                       page)]
;; Should return warning data structure (now two warnings: defaulted + missing)
      (is (= 2 (count (:warnings result)))
          "Should have two warnings")
      (is (= :defaulted-template (-> result :warnings first :type))
          "First should be defaulted-template warning")
      (is (= :missing-template (-> result :warnings second :type))
          "Second should be missing-template warning")
      (is (= :typed-page (-> result :warnings second :template-name))
          "Missing template warning should reference the template")
      (is (nil? (:html result))
          "Should not have :html when template is missing"))))