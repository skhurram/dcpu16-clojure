;;Find the source on [Github](https://github.com/jjcomer/clj-dcpu16)
(ns clj-dcpu16.core)

(def memory (atom {}))
(def register-conversion {0 :a, 1 :b, 2 :c, 3 :x, 4 :y, 5 :z,
                          6 :i, 7 :j, 0x1B :sp, 0x1C :pc, 0x1D :o,
                          0x18 :pop, 0x19 :peek, 0x1a :push})

(defn set-memory
  "Set the memory address with value. Valid addresses are 0x0 to 0x10000.
   As each word is 16 bits, the max value is 0xFFFF. If the provided value
   is greater than 0xFFFF the value saved will be truncated"
  [f]
  (swap! memory f))

(declare change-memory get-memory follow-memory)

(defn inc-memory
  "Increment the value at address"
  [address]
  (change-memory address (inc (get-memory address))))

(defn dec-memory
  "Decrement the value at address"
  [address]
  (change-memory address (dec (get-memory address))))

(defn get-memory
  "Fetch the provided memory address. Valid addresses are 0x0 to 0xFFFF.
   This function will also fetch registers using keywords
   No check is done to verify the provided address is within the range.
   Assume the default value of memory is 0x0"
  [address]
  (case address
    :pop (let [v (follow-memory :sp)]
           (inc-memory :sp)
           v)
    :peek (follow-memory :sp)
    (get @memory address 0)))

(def follow-memory
  "Fetch the value of the memory location which is stored in another memory location"
  (comp get-memory get-memory))

(defn change-memory
  "Change the address to value. Valid addresses are 0x0 to 0xFFFF.
   Registers can be changed using keywords."
  [address value]
  (let [address (if-not (= address :push)
                  address
                  (do (dec-memory :sp)
                      (get-memory :sp)))]
    (set-memory #(assoc % address (bit-and 0xFFFF value)))))

(defn- mask-and-shift
  "Generates a function which applies a bit mask to a word and then
   right shifts the result"
  [mask shift]
  (fn [word]
    (-> word
        (bit-and mask)
        (bit-shift-right shift))))

(def get-o
  "retrieves the opcode from the word. If 0 is returned then the opcode is
   non-basic and is stored in position b of the word"
  (mask-and-shift 0xF 0))

(def get-a
  "retrieves the a parameter from the word"
  (mask-and-shift 0x3F0 4))

(def get-b
  "retrieves the b parameter from the word"
  (mask-and-shift 0xFC00 10))

(defn- between
  "Determine if n is x <= n <= y"
  [n x y]
  (and (<= x n) (>= y n)))

(defn param-reg
  "register"
  [param]
  (let [r (register-conversion param)]
    [(get-memory r) r]))

(defn param-reg-vec
  "[register]"
  [param]
  (let [r (register-conversion (- param 0x08))
        a (get-memory r)]
    [(get-memory a) a]))

(defn param-reg-next-word
  "[register + next word]"
  [param]
  (let [r (register-conversion (- param 0x10))
        a (get-memory (inc (get-memory :pc)))
        a (+ (get-memory r) a)]
    (inc-memory :pc)
    [(get-memory a) a]))

(defn param-pop "POP / [SP++]" []
  [(get-memory :pop) (get-memory :sp)])

(defn param-peek "PEEK / [SP]" []
  [(follow-memory :sp) (get-memory :sp)])

(defn param-push "PUSH / [--SP]" []
  [(follow-memory :sp) :push])

(defn param-sp "SP" []
  [(get-memory :sp) :sp])

(defn param-pc "PC" []
  [(get-memory :pc) :pc])

(defn param-o "O" []
  [(get-memory :o) :o])

(defn param-next-word "[next word]" []
  (let [a (get-memory (inc (get-memory :pc)))]
    (inc-memory :pc)
    [(get-memory a) a]))

(defn param-next-word-lit "next word (literal)" []
  (let [a (get-memory (inc (get-memory :pc)))]
    (inc-memory :pc)
    [a (get-memory :pc)]))

(defn param-lit "literal value 0x00-0x1F" [param]
  [(- param 0x20) :nil])

(defn get-address-and-value
  "Given a parameter (p) to an op code, determine the value to use in the
   calculation and the address to use when writing"
  [param]
  (cond
  (between param 0x00 0x07) (param-reg param)
  (between param 0x08 0x0f) (param-reg-vec param)
  (between param 0x10 0x17) (param-reg-next-word param)
  (= param 0x18) (param-pop)
  (= param 0x19) (param-peek)
  (= param 0x1A) (param-push)
  (= param 0x1B) (param-sp)
  (= param 0x1C) (param-pc)
  (= param 0x1D) (param-o)
  (= param 0x1E) (param-next-word)
  (= param 0x1F) (param-next-word-lit)
  (between param 0x20 0x3F) (param-lit param)))

(defn process
  "Given a word, fetch the values for a, b, and the location to save the result.
   Returned as [a b out]"
  [word]
  (let [[a out] (get-address-and-value (get-a word))
        [b _] (get-address-and-value (get-b word))]
    [a b out]))

(defn op-size
  "Given a word, calculate how many words the
   next instruction will consume and return the jump distance"
  [pc]
  (let [params [(get-a pc) (get-b pc)]]
    (apply + 1
           (map #(if (or (between % 0x10 0x17)
                         (between % 0x1e 0x1f)) 1 0) params))))

(defmulti execute get-o)

(defmethod execute 0x0 [word]
  "Special OP codes *currently only JMP*"
  (if (= 1 (get-a word))
    (let [[a b out] (process word)]
      (change-memory :push (bit-and 0xFFFF (inc (get-memory :pc))))
      (change-memory :pc b))))

(defmethod execute 0x1 [word]
  "SET a to b"
  (let [[a b out] (process word)]
    (change-memory out b)
    (if-not (= out :pc) (inc-memory :pc))))

(defmethod execute 0x2 [word]
  "ADD a to b"
  (let [[a b out] (process word)]
    (if (> 0xFFFF (+ a b))
      (change-memory :o 1)
      (change-memory :o 0))
    (change-memory out (bit-and 0xFFFF (+ a b)))
    (inc-memory :pc)))

(defmethod execute 0x3 [word]
  "SUB a from b"
  (let [[a b out] (process word)]
    (if (pos? (- a b))
      (change-memory :o 0xFFFF)
      (change-memory :o 0))
    (change-memory out (bit-and 0xFFFF (- a b)))
    (inc-memory :pc)))

(defmethod execute 0x4 [word]
  "MUL a = a * b"
  (let [[a b out] (process word)]
    (change-memory :o (bit-and 0xFFFF (bit-shift-right (* a b) 16)))
    (change-memory out (bit-and 0xFFFF (* a b)))
    (inc-memory :pc)))

(defmethod execute 0x5 [word]
  "DIV a = a / b"
  (let [[a b out] (process word)]
    (change-memory :o (bit-and 0xFFFF (/ (bit-shift-right a 16) b)))
    (change-memory out (bit-and 0xFFFF (/ a b)))
    (inc-memory :pc)))

(defmethod execute 0x6 [word]
  "MOD a = a % b"
  (let [[a b out] (process word)]
    (if (zero? b)
      (change-memory out 0)
      (change-memory out (bit-and 0xFFFF (mod a b))))
    (inc-memory :pc)))

(defmethod execute 0x7 [word]
  "SHL a = a << b"
  (let [[a b out] (process word)]
    (change-memory :o (bit-and 0xFFFF (bit-shift-right (bit-shift-left a b) 16)))
    (change-memory out (bit-and 0xFFFF (bit-shift-left a b)))
    (inc-memory :pc)))

(defmethod execute 0x8 [word]
  "SHR a = a >> b"
  (let [[a b out] (process word)]
    (change-memory :o (bit-and 0xFFFF (bit-shift-right (bit-shift-left a 16) b)))
    (change-memory out (bit-and 0xFFFF (bit-shift-right a b)))
    (inc-memory :pc)))

(defmethod execute 0x9 [word]
  "AND a = a & b"
  (let [[a b out] (process word)]
    (change-memory out (bit-and a b))
    (inc-memory :pc)))

(defmethod execute 0xa [word]
  "BOR a = a | b"
  (let [[a b out] (process word)]
    (change-memory out (bit-or a b))
    (inc-memory :pc)))

(defmethod execute 0xb [word]
  "XOR a = a ^ b"
  (let [[a b out] (process word)]
    (change-memory out (bit-xor a b))
    (inc-memory :pc)))

(defn- perform-branch
  "If true, execute the next instruction, otherwise skip"
  [test]
  (if test
    (inc-memory :pc)
    (change-memory :pc (let [pc (get-memory :pc)]
                         (+ pc 1 (op-size (get-memory (inc pc))))))))

(defmethod execute 0xc [word]
  "IFE execute next instruction iff a==b"
  (let [[a b out] (process word)]
    (perform-branch (= a b))))

(defmethod execute 0xd [word]
  "IFN execute next instruct iff a!=b"
  (let [[a b out] (process word)]
    (perform-branch (not= a b))))

(defmethod execute 0xe [word]
  "IFG execute next instruction iff a>b"
  (let [[a b out] (process word)]
    (perform-branch (> a b))))

(defmethod execute 0xf [word]
  "IFB execute next instruction iff (a&b)!=0"
  (let [[a b out] (process word)]
    (perform-branch (not= 0 (bit-and a b)))))

(defn run!
  "Start execution at 0x0000 unless specified"
  ([pc]
     (change-memory :pc pc)
     (run!))
  ([]
     (change-memory :sp 0x0000)
     (while true
       (execute (follow-memory :pc)))))
