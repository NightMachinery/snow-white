(ns snow-white.ids
  "Opaque random identifiers: auth ids (per-browser identity) and migration tokens
  (used to move an identity to another device without exposing the auth id).

  Learning note: Clojure has no string-building ceremony — we lean on Java interop
  (`java.security.SecureRandom`, `java.util.Base64`) directly. `(java.util.Base64/...)`
  is how you call a static method on a Java class."
  (:import [java.security SecureRandom]
           [java.util Base64]))

(def ^:private rng (SecureRandom.))

(defn token
  "A URL-safe random token with `n` bytes of entropy (default 18 ~ 144 bits)."
  ([] (token 18))
  ([n]
   (let [bs (byte-array n)]
     (.nextBytes rng bs)
     (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))))

(defn auth-id
  "Identity token a browser stores in localStorage. Distinct alias for clarity."
  []
  (token 18))
