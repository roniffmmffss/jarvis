package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class JarvisService : Service(), TextToSpeech.OnInitListener {
    companion object {
        @Volatile var running = false
        val logText = StringBuilder()
        @Volatile var onLog: ((String) -> Unit)? = null
        fun addLog(s: String) {
            synchronized(logText) {
                logText.append("\n").append(s).append("\n")
                if (logText.length > 6000) logText.delete(0, 2000)
            }
            onLog?.invoke(s)
        }
    }

    private class Sym(val screener: String, val tv: String, val bin: String?)

    private lateinit var tts: TextToSpeech
    private var sr: SpeechRecognizer? = null
    private var wl: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    @Volatile private var speaking = false
    @Volatile private var listening = false
    @Volatile private var busy = false
    @Volatile private var ttsReady = false
    @Volatile private var uid = 0
    private var awakeUntil = 0L
    private var pending: ((Boolean) -> Unit)? = null
    private var pendingUntil = 0L

    private val WAKE = listOf("hey jarvis", "হেই জার্ভিস", "জার্ভিস", "জারভিস", "জার্বিস",
        "জারবিস", "jarvis", "তালহা", "talha")
    private val FILLER = setOf("hey", "hi", "হেই", "হে", "হ্যালো")
    private val YES = setOf("হ্যাঁ", "হ্যা", "হা", "জি", "yes", "ইয়েস", "ok", "okay", "ওকে", "করো")
    private val NO = setOf("না", "নো", "no", "cancel", "বাতিল", "নাহ")
    private val APPS = mapOf("ইউটিউব" to "youtube", "ফেসবুক" to "facebook",
        "হোয়াটসঅ্যাপ" to "whatsapp", "টেলিগ্রাম" to "telegram", "ক্রোম" to "chrome",
        "ট্রেডিং ভিউ" to "tradingview", "ট্রেডিংভিউ" to "tradingview",
        "বাইন্যান্স" to "binance", "ক্যামেরা" to "camera", "সেটিংস" to "settings",
        "ইনস্টাগ্রাম" to "instagram")
    private val SYS = "তুমি Jarvis, একজন ট্রেডারের স্মার্ট সহকারী। বাংলায় কথা বলো, কিন্তু " +
        "ট্রেডিং ও টেক শব্দ ইংরেজিতেই রাখো (Entry, Stop Loss, Buy, Sell, Support, Resistance, Trend)। " +
        "সংক্ষেপে উত্তর দাও। কোনো markdown, তারকা বা ইমোজি ব্যবহার করবে না।"

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1,
            Intent(this, JarvisService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis চালু আছে")
            .setContentText("বলুন: Hey Jarvis")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "বন্ধ করুন", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wl").apply { acquire() }
        running = true
        addLog("Jarvis ব্যাকগ্রাউন্ডে চালু। বলুন: Hey Jarvis")
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        addLog("Jarvis বন্ধ হয়েছে")
        sr?.destroy()
        tts.shutdown()
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { addLog("কথা বলার ইঞ্জিন চালু হয়নি"); return }
        val r = tts.setLanguage(Locale("bn", "BD"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            tts.setLanguage(Locale.US)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { resume(id) }
            override fun onError(id: String?) { resume(id) }
            override fun onStop(id: String?, interrupted: Boolean) { resume(id) }
        })
        ttsReady = true
        reply("Jarvis রেডি। Hey Jarvis বলে ডাকুন")
    }

    private fun resume(id: String?) {
        if (id == "u$uid") { speaking = false; ui.postDelayed({ listen() }, 400) }
    }

    private fun key() = prefs.getString("key", "") ?: ""
    private fun add(who: String, t: String) = addLog("$who: $t")

    private fun speak(t: String) {
        if (!ttsReady) return
        val clean = t.replace("*", "").replace("#", "").take(1500)
        uid++
        speaking = true
        listening = false
        ui.post { sr?.cancel() }
        if (tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "u$uid") != TextToSpeech.SUCCESS) {
            speaking = false
            ui.post { listen() }
        }
    }

    private fun reply(t: String) { add("Jarvis", t); speak(t) }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            add("Jarvis", "Google voice (Speech Services) পাওয়া যায়নি"); return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onError(e: Int) {
                listening = false
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    add("Jarvis", "মাইকের অনুমতি নেই"); return
                }
                if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) sr?.cancel()
                ui.postDelayed({ listen() }, 700)
            }
            override fun onResults(b: Bundle?) {
                listening = false
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) ui.postDelayed({ listen() }, 300) else heard(t)
            }
        })
        sr = r
        listen()
    }

    private fun listen() {
        if (!running || speaking || busy || listening) return
        val r = sr ?: return
        listening = true
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    private fun heard(raw: String) {
        val low = raw.lowercase().trim()
        val hasWake = WAKE.any { low.contains(it) }
        val now = System.currentTimeMillis()
        val p = pending
        if (p != null) {
            if (now < pendingUntil && !hasWake) {
                val w = low.split(Regex("[\\s,।.?!]+"))
                when {
                    w.any { it in YES } -> { pending = null; add("আপনি", raw); p(true) }
                    w.any { it in NO } -> { pending = null; add("আপনি", raw); p(false) }
                    else -> reply("হ্যাঁ বা না বলুন")
                }
                return
            }
            pending = null
        }
        if (!hasWake && now >= awakeUntil) { listen(); return }
        add("আপনি", raw)
        var cmd = low
        for (w in WAKE) cmd = cmd.replace(w, " ")
        cmd = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() && it !in FILLER }
            .joinToString(" ").trim(' ', ',', '.', '।', '?', '!')
        if (cmd.isEmpty()) { awakeUntil = now + 15000; reply("জি বলুন"); return }
        awakeUntil = 0
        if (cmd.contains("বন্ধ হ") || cmd == "stop" || cmd == "স্টপ") {
            reply("ঠিক আছে, বন্ধ হচ্ছি। আবার দরকার হলে অ্যাপ থেকে চালু করবেন")
            ui.postDelayed({ stopSelf() }, 3500)
            return
        }
        process(cmd)
    }

    private fun process(cmd: String) {
        busy = true
        listening = false
        ui.post { sr?.cancel() }
        thread {
            try { execute(cmd) }
            catch (e: Exception) { reply("সমস্যা হয়েছে: " + (e.message ?: "").take(100)) }
            finally { busy = false; ui.post { listen() } }
        }
    }

    private fun execute(raw: String) {
        var t = raw
        for ((k, v) in APPS) t = t.replace(k, v)
        if (t.contains("নোট") && (t.contains("শোনা") || t.contains("পড়"))) {
            reply(prefs.getString("note", null) ?: "কোনো নোট নেই"); return
        }
        if ((t.contains("open") || t.contains("খোল") || t.contains("চালু কর")) && openApp(t)) return
        val key = key()
        if (key.isEmpty()) { reply("আগে Gemini key সেভ করুন"); return }
        val s = symbolOf(t)
        if (s != null) trade(s, raw, key) else reply(gemini(raw, key))
    }

    private fun openApp(t: String): Boolean {
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            if (label.length > 1 && t.contains(label.lowercase())) {
                val li = pm.getLaunchIntentForPackage(ri.activityInfo.packageName) ?: continue
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                reply("$label খুলছি")
                ui.postDelayed({
                    try { startActivity(li) } catch (e: Exception) {
                        add("Jarvis", "অ্যাপ খুলতে পারিনি। অ্যাপের ভেতরের 'অন্য অ্যাপ খোলার অনুমতি' দিন")
                    }
                }, 1200)
                return true
            }
        }
        return false
    }

    private fun hit(t: String, k: String) = Regex("(^|[^a-z])$k([^a-z]|$)").containsMatchIn(t)

    private fun symbolOf(t: String): Sym? {
        Regex("[a-z]{2,6}usdt").find(t)?.let {
            val s = it.value.uppercase(); return Sym("crypto", "BINANCE:$s", s)
        }
        val crypto = mapOf("btc" to "BTCUSDT", "bitcoin" to "BTCUSDT", "বিটকয়ন" to "BTCUSDT",
            "বিটকয়েন" to "BTCUSDT", "eth" to "ETHUSDT", "ethereum" to "ETHUSDT",
            "ইথেরিয়াম" to "ETHUSDT", "sol" to "SOLUSDT", "solana" to "SOLUSDT",
            "সোলানা" to "SOLUSDT", "bnb" to "BNBUSDT", "xrp" to "XRPUSDT", "doge" to "DOGEUSDT")
        for ((k, v) in crypto) if (hit(t, k)) return Sym("crypto", "BINANCE:$v", v)
        val fx = mapOf("gold" to "XAUUSD", "xauusd" to "XAUUSD", "গোল্ড" to "XAUUSD",
            "সোনা" to "XAUUSD", "eurusd" to "EURUSD", "gbpusd" to "GBPUSD", "usdjpy" to "USDJPY")
        for ((k, v) in fx) if (hit(t, k)) return Sym("forex", "OANDA:$v", null)
        return null
    }

    private fun n(a: JSONArray, i: Int): String =
        if (a.isNull(i)) "NA" else String.format(Locale.US, "%.5g", a.getDouble(i))

    private fun tvData(s: Sym): String {
        val cols = listOf("close", "Recommend.All", "RSI", "MACD.macd", "MACD.signal",
            "EMA20", "EMA50", "ADX", "ATR")
        val tfs = listOf("15" to "15m", "60" to "1h", "240" to "4h", "" to "1D")
        val all = JSONArray()
        for ((tf, _) in tfs) for (c in cols) all.put(if (tf == "") c else "$c|$tf")
        val body = JSONObject()
            .put("symbols", JSONObject().put("tickers", JSONArray().put(s.tv))
                .put("query", JSONObject().put("types", JSONArray())))
            .put("columns", all)
        val r = JSONObject(http("https://scanner.tradingview.com/${s.screener}/scan", body.toString()))
        val d = r.getJSONArray("data").getJSONObject(0).getJSONArray("d")
        val sb = StringBuilder()
        var i = 0
        for ((_, name) in tfs) {
            sb.append("$name: ")
            for (c in cols) { sb.append("$c=${n(d, i)} "); i++ }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun levels(sym: String): String {
        val sb = StringBuilder()
        for (iv in listOf("1h", "4h")) {
            val arr = JSONArray(http("https://data-api.binance.vision/api/v3/klines?symbol=$sym&interval=$iv&limit=50"))
            var hi = 0.0
            var lo = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val k = arr.getJSONArray(i)
                hi = maxOf(hi, k.getString(2).toDouble())
                lo = minOf(lo, k.getString(3).toDouble())
            }
            sb.append("$iv গত ৫০ ক্যান্ডেল: High=$hi Low=$lo\n")
        }
        return sb.toString()
    }

    private fun trade(s: Sym, req: String, key: String) {
        val name = s.tv.substringAfter(':')
        reply("$name বিশ্লেষণ করছি")
        var tv = ""
        var lv = ""
        try { tv = tvData(s) } catch (e: Exception) { add("Jarvis", "TradingView: " + (e.message ?: "").take(80)) }
        if (s.bin != null) {
            try { lv = levels(s.bin) } catch (e: Exception) { add("Jarvis", "Binance: " + (e.message ?: "").take(80)) }
        }
        if (tv.isEmpty() && lv.isEmpty()) { reply("ডেটা আনতে পারিনি, ইন্টারনেট দেখুন"); return }
        if (tv.isEmpty()) tv = "TradingView ডেটা পাওয়া যায়নি"
        val prompt = "আমি একজন ট্রেডার। ইনস্ট্রুমেন্ট: $name\n" +
            "TradingView ইন্ডিকেটর (Recommend.All: -1=Strong Sell, 0=Neutral, +1=Strong Buy; ATR=ভোলাটিলিটি):\n$tv\n$lv\n" +
            "আমার কথা: $req\n" +
            "বাংলায় উত্তর দাও কিন্তু ট্রেডিং শব্দ ইংরেজিতে রাখো। সর্বোচ্চ ১২০ শব্দ, কোনো markdown নয়। " +
            "এই ক্রমে: Trend (15m/1h/4h/1D মিলিয়ে), Support-Resistance, Signal (Buy/Sell/Wait), " +
            "Entry, Stop Loss (ATR দিয়ে), TP1, TP2, Risk:Reward, ঝুঁকি টিপ (ক্যাপিটালের ১-২%)। " +
            "শেষে বলো এটা নিশ্চিত নয়, শুধু বিশ্লেষণ।"
        val ans = gemini(prompt, key)
        pending = { yes ->
            if (yes) {
                prefs.edit().putString("note", "$name (${java.util.Date()})\n$ans").apply()
                reply("নোট হয়েছে। নোট শোনাও বললে আবার শোনাব। অর্ডার আমি দিই না, আপনি নিজে সেট করবেন।")
            } else reply("ঠিক আছে, বাতিল।")
        }
        pendingUntil = System.currentTimeMillis() + 120000
        reply(ans + "\n\nএই প্ল্যান কি নোট করে রাখব? হ্যাঁ বা না বলুন।")
    }

    private fun gemini(q: String, key: String): String {
        val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYS)))
        val body = JSONObject().put("systemInstruction", sys)
            .put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", q)))))
        val r = JSONObject(http(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            body.toString(), key))
        return r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }

    private fun http(url: String, body: String? = null, key: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (key != null) c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val s = (if (code < 400) c.inputStream else c.errorStream) ?: throw Exception("HTTP $code")
        val txt = s.bufferedReader().use { it.readText() }
        if (code >= 400) throw Exception("HTTP $code: ${txt.take(150)}")
        return txt
    }
}
