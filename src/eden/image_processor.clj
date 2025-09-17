(ns eden.image-processor
  "Image processing using imgscalr for high-quality scaling.
   
   Usage:
   (process-image {:source-path \"src/assets/images/hero.jpg\"
                   :width 800
                   :height 600
                   :output-dir \".temp/images\"})
   
   Features:
   - High-quality image scaling using imgscalr
   - Preserves image format (no conversion)
   - Generates placeholders for missing images"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.imgscalr Scalr]
           [org.imgscalr Scalr$Method]
           [org.imgscalr Scalr$Mode]
           [javax.imageio ImageIO]
           [java.io File]
           [java.awt Graphics2D Color Font BasicStroke RenderingHints]
           [java.awt.image BufferedImage]))

;; (set! *warn-on-reflection* true)

(defn- get-image-extension
  "Get the file extension from a path"
  [source-path]
  (when source-path
    (-> source-path
        (str/split #"\.")
        last
        str/lower-case)))

(defn- generate-output-filename
  "Generate output filename with dimensions"
  [source-path width height]
  (let [file (io/file source-path)
        file-name (.getName ^File file)
        parts (str/split file-name #"\.")
        name-without-ext (if (> (count parts) 1)
                           (str/join "." (butlast parts))
                           file-name)
        extension (get-image-extension source-path)
        ;; Build filename based on dimensions
        output-name (cond
                      ;; Both width and height specified
                      (and width height)
                      (str name-without-ext "-" width "x" height "." extension)

                      ;; Only width specified
                      width
                      (str name-without-ext "-" width "x." extension)

                      ;; Only height specified
                      height
                      (str name-without-ext "-x" height "." extension)

                      ;; No processing - keep original name
                      :else
                      file-name)]
    output-name))

(defn- create-placeholder-image
  "Create a simple placeholder BufferedImage"
  [^Integer width ^Integer height ^String text]
  (let [img (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        g ^Graphics2D (.createGraphics img)]
    ;; Enable antialiasing
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                       RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING
                       RenderingHints/VALUE_TEXT_ANTIALIAS_ON)

    ;; Background
    (.setColor g (Color. 224 224 224)) ; #e0e0e0
    (.fillRect g 0 0 width height)

    ;; Border
    (.setColor g (Color. 153 153 153)) ; #999
    (.setStroke g (BasicStroke. 2.0))
    (let [margin (int (* 0.1 (min width height)))]
      (.drawRect g margin margin (- width (* 2 margin)) (- height (* 2 margin))))

    ;; Text
    (.setColor g (Color. 51 51 51)) ; #333
    (let [font-size (int (* 0.1 (min width height)))
          font (Font. "Arial" Font/BOLD font-size)]
      (.setFont g font)
      (let [fm (.getFontMetrics g)
            text-width (.stringWidth fm text)]
        (.drawString g text
                     (int (/ (- width text-width) 2))
                     (int (* 0.45 height)))))

    ;; Dimensions text
    (let [dims-text (str width "×" height)
          font-size (int (* 0.08 (min width height)))
          font (Font. "Arial" Font/PLAIN font-size)]
      (.setFont g font)
      (.setColor g (Color. 153 153 153)) ; #999
      (let [fm (.getFontMetrics g)
            text-width (.stringWidth fm dims-text)]
        (.drawString g dims-text
                     (int (/ (- width text-width) 2))
                     (int (* 0.55 height)))))

    (.dispose g)
    img))

(defn- generate-placeholder
  "Generate placeholder image when source is missing"
  [{:keys [output-path width height source-path]
    :or {}}]
  (let [file (io/file source-path)
        filename (.getName ^File file)
        buffered-image (create-placeholder-image width height filename)
        extension (get-image-extension source-path)
        output-file (io/file output-path)]

    ;; Ensure parent directory exists
    (io/make-parents output-file)

    ;; Write the image using ImageIO
    (ImageIO/write ^BufferedImage buffered-image ^String extension ^File output-file)

    {:output-path (.getAbsolutePath ^File output-file)
     :placeholder? true}))

(defn process-image
  "Process a single image with given options.
   Generates placeholder if source doesn't exist.
   Copies as-is if no width/height specified.
   
   Options:
   - :allow-stretch - Set to true to allow stretching images (default: false)"
  [{:keys [source-path width height output-dir allow-stretch]}]
  (try
    (let [output-filename (generate-output-filename source-path width height)
          output-file (io/file output-dir output-filename)
          output-path (.getAbsolutePath ^File output-file)
          source-file (io/file source-path)]

      ;; Ensure output directory exists
      (io/make-parents output-file)
      (if (.exists ^File source-file)
        ;; Process or copy the actual image
        (if (or width height)
          ;; Scale with imgscalr
          (let [original ^BufferedImage (ImageIO/read source-file)
                ;; Empty BufferedImageOp array for varargs
                ops ^"[Ljava.awt.image.BufferedImageOp;" (make-array java.awt.image.BufferedImageOp 0)
                ;; Use ULTRA_QUALITY for best results
                ^BufferedImage scaled (cond
                                        ;; Both dimensions: only use FIT_EXACT if stretching is allowed
                                        (and width height)
                                        (if allow-stretch
                                          ;; User explicitly wants stretching
                                          (Scalr/resize original
                                                        Scalr$Method/ULTRA_QUALITY
                                                        Scalr$Mode/FIT_EXACT
                                                        ^Integer width
                                                        ^Integer height
                                                        ops)
                                          ;; Default: fit within bounds maintaining aspect ratio
                                          (Scalr/resize original
                                                        Scalr$Method/ULTRA_QUALITY
                                                        Scalr$Mode/AUTOMATIC
                                                        ^Integer width
                                                        ^Integer height
                                                        ops))

                                        ;; Width only: scale maintaining aspect ratio
                                        width
                                        (Scalr/resize original
                                                      Scalr$Method/ULTRA_QUALITY
                                                      ^Integer width
                                                      ops)

                                        ;; Height only: use FIT_TO_HEIGHT mode
                                        :else
                                        (Scalr/resize original
                                                      Scalr$Method/ULTRA_QUALITY
                                                      Scalr$Mode/FIT_TO_HEIGHT
                                                      ^Integer height
                                                      ops))
                extension (get-image-extension source-path)]

            ;; Write the scaled image
            (ImageIO/write scaled ^String extension output-file)

            ;; Flush the scaled image to free memory
            (.flush scaled)

            {:output-path output-path
             :placeholder? false})

          ;; Just copy the file as-is
          (do
            (io/copy source-file output-file)
            {:output-path output-path
             :placeholder? false}))

        ;; Generate placeholder
        (generate-placeholder {:output-path output-path
                               :width (or width 400)
                               :height (or height 300)
                               :source-path source-path})))

    (catch Exception e
      {:error (.getMessage e)})))

(comment
  ;; Test scaling
  (process-image {:source-path "site/assets/images/values-technology.png"
                  :width 295
                  :height 295
                  :output-dir ".temp/images"})

  ;; Test placeholder
  (process-image {:source-path "site/assets/images/missing.png"
                  :width 400
                  :height 300
                  :output-dir ".temp/images"}))
