package com.example.myapp

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

const val MP = ViewGroup.LayoutParams.MATCH_PARENT
const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

// junk category ids (also the display order)
const val C_APPCACHE = 0
const val C_TEMP = 1
const val C_LOGS = 2
const val C_THUMBS = 3
const val C_TRASH = 4
const val C_CACHEDIR = 5
const val C_ADSDK = 6
const val C_EMPTYDIR = 7
const val C_VCACHE = 8
const val C_BACKUP = 9
const val C_PARTIAL = 10
const val C_APKS = 11
const val C_EMPTYFILE = 12
const val C_RESIDUAL = 13
const val C_HIDDEN = 14
const val C_STATUS = 15
const val C_SHOTS = 16
const val C_OLDDL = 17
const val C_MSGR = 18
const val C_CHATBAK = 19

// row types for the list adapter
const val T_HEAD = 0
const val T_ITEM = 1
const val T_APP = 2
const val T_TEXT = 3
const val T_GROUP = 4

object Col {
    val BG = Color.parseColor("#0E151D")
    val CARD = Color.parseColor("#17212C")
    val CARD2 = Color.parseColor("#1F2B38")
    val ACC = Color.parseColor("#19C37D")
    val WARN = Color.parseColor("#F5A623")
    val BAD = Color.parseColor("#FF5C5C")
    val TXT = Color.parseColor("#E8EEF4")
    val SUB = Color.parseColor("#8FA0B3")
    val DARKTXT = Color.parseColor("#06210F")
}

fun fmt(b: Long): String {
    if (b < 1024L) return b.toString() + " B"
    val kb = b / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun ago(ms: Long): String {
    val days = (System.currentTimeMillis() - ms) / 86400000L
    if (days <= 0L) return "used today"
    if (days < 31L) return "used " + days.toString() + " d ago"
    val months = days / 30L
    return "used " + months.toString() + " mo ago"
}

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

class FItem(val path: String, val size: Long, val isDir: Boolean, val cat: Int, var checked: Boolean)

class JCat(val id: Int, val title: String, val desc: String, val review: Boolean) {
    val items = ArrayList<FItem>()
    var total = 0L
    var expanded = false

    fun recalc() {
        var t = 0L
        for (i in items) t += i.size
        total = t
    }

    fun selSize(): Long {
        var t = 0L
        for (i in items) if (i.checked) t += i.size
        return t
    }

    fun selCount(): Int {
        var c = 0
        for (i in items) if (i.checked) c++
        return c
    }
}

class DupGroup(val size: Long) {
    val files = ArrayList<FItem>()
    var waste = 0L
}

class AppRec(val pkg: String, val label: String, val system: Boolean) {
    var cache = -1L
    var total = -1L
    var lastUsed = 0L
}

class ScanData {
    val cats = ArrayList<JCat>()
    val big = ArrayList<FItem>()
    val photos = ArrayList<String>()
    val jpegs = ArrayList<FItem>()
    val sizeMap = HashMap<Long, ArrayList<String>>()
    val typeSize = LongArray(7)
    val typeCount = IntArray(7)
    var fileCount = 0
    var finished = 0L

    init {
        cats.add(JCat(C_APPCACHE, "Cleaner app cache", "Temporary files created by this app", false))
        cats.add(JCat(C_TEMP, "Temporary files", ".tmp, .temp, swap and ~ files", false))
        cats.add(JCat(C_LOGS, "Log & crash files", ".log, .dmp, .trace files and log folders", false))
        cats.add(JCat(C_THUMBS, "Thumbnail caches", "Hidden gallery thumbnails (.thumbnails, .thumbdata)", false))
        cats.add(JCat(C_TRASH, "Trash & recycle bins (review)", "Recently deleted items kept by gallery / file apps", true))
        cats.add(JCat(C_CACHEDIR, "Hidden cache folders", "Folders named .cache, .tmp, .temp", false))
        cats.add(JCat(C_ADSDK, "Ad & analytics leftovers", "Known hidden folders left by ad / tracking SDKs", false))
        cats.add(JCat(C_EMPTYDIR, "Empty folders (review)", "Folders with nothing inside - you may want them", true))
        cats.add(JCat(C_VCACHE, "Cache / temp folders (review)", "Visible folders named cache, tmp or temp - check first", true))
        cats.add(JCat(C_BACKUP, "Backup & old files (review)", ".bak, .old, .orig files - may be wanted backups", true))
        cats.add(JCat(C_PARTIAL, "Incomplete downloads (review)", ".crdownload, .part, .partial files", true))
        cats.add(JCat(C_APKS, "Installer files (review)", ".apk / .xapk / .apks files", true))
        cats.add(JCat(C_EMPTYFILE, "Empty files (review)", "Files with 0 bytes", true))
        cats.add(JCat(C_RESIDUAL, "Leftovers of uninstalled apps (review)", "Package-named folders of apps that are no longer installed", true))
        cats.add(JCat(C_HIDDEN, "Other hidden folders (review)", "Hidden folders in the storage root", true))
        cats.add(JCat(C_STATUS, "Viewed status cache", "WhatsApp / Business status files cached after viewing", false))
        cats.add(JCat(C_SHOTS, "Screenshots (review)", "Images inside Screenshots folders", true))
        cats.add(JCat(C_OLDDL, "Old downloads (review)", "Files in Download folders older than 60 days", true))
        cats.add(JCat(C_MSGR, "Messenger media (review)", "WhatsApp / Telegram media folders - received photos, videos, voice notes", true))
        cats.add(JCat(C_CHATBAK, "Old chat backups (review)", "Older WhatsApp msgstore backups (newest one is kept)", true))
    }

    fun add(cat: Int, path: String, size: Long, isDir: Boolean) {
        val c = cats[cat]
        c.items.add(FItem(path, size, isDir, cat, !c.review))
    }
}

class TNode(
    val path: String,
    val name: String,
    val isDir: Boolean,
    size0: Long,
    val level: Int,
    val hidden: Boolean,
    val mtime: Long
) {
    @Volatile var size = size0
    var expanded = false
    var loaded = false
    @Volatile var loading = false
    var restricted = false
    var checked = false
    var children = ArrayList<TNode>()
    var itemCount = 0
}

object Vols {
    @Volatile var roots: List<String> = ArrayList<String>()
}

class Row(val type: Int) {
    var title = ""
    var sub = ""
    var right = ""
    var checked = false
    var showCb = false
    var pkg: String? = null
    var cat: JCat? = null
    var item: FItem? = null
    var app: AppRec? = null
    var group: DupGroup? = null
    var rightColor = 0
    var expanded = false
    var tag = ""
    var node: TNode? = null
    var indentDp = -1
}

open class RowL {
    open fun onRow(r: Row) {}
    open fun onLong(r: Row) {}
    open fun onCheck(r: Row, checked: Boolean) {}
}

fun delEmptyTree(f: File): Boolean {
    val kids = f.listFiles() ?: return false
    for (k in kids) {
        if (k.isDirectory) {
            if (!delEmptyTree(k)) return false
        } else {
            return false
        }
    }
    return f.delete()
}

fun isLinkFile(f: File): Boolean {
    return try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (e: Exception) {
        false
    }
}

fun treeSize(d: File): Long {
    var total = 0L
    val stack = ArrayList<File>()
    stack.add(d)
    while (stack.isNotEmpty()) {
        val cur = stack.removeAt(stack.size - 1)
        val kids = cur.listFiles() ?: continue
        for (k in kids) {
            if (k.isDirectory) {
                if (!isLinkFile(k)) stack.add(k)
            } else {
                total += k.length()
            }
        }
    }
    return total
}

// Top-level storage folders that must never be deleted.
fun isProtectedPath(path: String): Boolean {
    val roots = ArrayList<String>()
    roots.add(Environment.getExternalStorageDirectory().path)
    for (r in Vols.roots) if (!roots.contains(r)) roots.add(r)
    val names = arrayOf(
        "DCIM", "Pictures", "Download", "Documents", "Music", "Movies", "Android",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Recordings", "Audiobooks"
    )
    for (root in roots) {
        if (path == root || path == root + "/") return true
        for (n in names) {
            if (path == root + "/" + n) return true
        }
    }
    return false
}

// Deletes the given items; paths that are gone afterwards are added to `deleted`. Returns bytes freed.
fun deleteFItems(items: List<FItem>, deleted: ArrayList<String>): Long {
    var freed = 0L
    for (fi in items) {
        if (isProtectedPath(fi.path)) continue
        val f = File(fi.path)
        val existed = f.exists()
        val good = if (!existed) {
            true
        } else if (fi.isDir) {
            if (fi.cat == C_EMPTYDIR) delEmptyTree(f) else f.deleteRecursively()
        } else {
            f.delete()
        }
        if (good) {
            if (existed) freed += fi.size
            deleted.add(fi.path)
        }
    }
    return freed
}

// Used by the weekly auto-clean job: scans and deletes only the SAFE (non-review) categories.
fun runAutoClean(ctx: Context) {
    val ok = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    if (!ok) return
    val prefs = ctx.getSharedPreferences("cleaner", Context.MODE_PRIVATE)
    val inst = HashSet<String>()
    for (ai in ctx.packageManager.getInstalledApplications(0)) inst.add(ai.packageName.lowercase())
    val ex = ArrayList<String>()
    val raw = prefs.getString("excluded", "") ?: ""
    for (x in raw.split("\n")) {
        val t = x.trim()
        if (t.isNotEmpty()) ex.add(t)
    }
    val data = ScanData()
    val sc = Scanner(ctx, Environment.getExternalStorageDirectory(), inst, ex, data)
    sc.run()
    val items = ArrayList<FItem>()
    for (c in data.cats) {
        if (c.review) continue
        for (i in c.items) items.add(i)
    }
    val deleted = ArrayList<String>()
    val freed = deleteFItems(items, deleted)
    if (deleted.isNotEmpty()) {
        try {
            MediaScannerConnection.scanFile(ctx, deleted.take(2000).toTypedArray(), null, null)
        } catch (e: Exception) {
        }
    }
    prefs.edit()
        .putLong("freed_total", prefs.getLong("freed_total", 0L) + freed)
        .putLong("last_clean", System.currentTimeMillis())
        .putLong("auto_freed", freed)
        .apply()
}

class AutoCleanService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val p = params
        Thread(Runnable {
            try {
                runAutoClean(applicationContext)
            } catch (e: Exception) {
            }
            jobFinished(p, false)
        }).start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Auto cache clean (Accessibility helper): opens each app's Storage page and taps "Clear cache"
// ─────────────────────────────────────────────────────────────────────────────

object CacheJob {
    @Volatile var running = false
    val queue = ArrayList<String>()
    @Volatile var index = 0
    @Volatile var cleaned = 0
    @Volatile var skipped = 0
    @Volatile var step = 0
    @Volatile var stepAt = 0L
    @Volatile var misses = 0
    @Volatile var result = ""
}

class CacheCleanService : AccessibilityService() {

    companion object {
        @Volatile var instance: CacheCleanService? = null
    }

    private val hnd = Handler(Looper.getMainLooper())
    private var pending = false
    private val clearWords = arrayOf("clear", "clean", "साफ", "हटा", "delete", "empty")

    private val tick = Runnable {
        pending = false
        try {
            process()
        } catch (e: Exception) {
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!CacheJob.running) return
            if (System.currentTimeMillis() - CacheJob.stepAt > 8000L) {
                CacheJob.skipped++
                next()
            }
            hnd.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        CacheJob.running = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CacheJob.running || event == null) return
        if (!pending) {
            pending = true
            hnd.postDelayed(tick, 350L)
        }
    }

    fun beginJob() {
        CacheJob.running = true
        CacheJob.index = 0
        CacheJob.cleaned = 0
        CacheJob.skipped = 0
        CacheJob.step = 0
        CacheJob.misses = 0
        CacheJob.result = ""
        CacheJob.stepAt = System.currentTimeMillis()
        hnd.removeCallbacks(watchdog)
        hnd.postDelayed(watchdog, 1000L)
        launch()
    }

    private fun launch() {
        if (!CacheJob.running) return
        if (CacheJob.index >= CacheJob.queue.size) {
            finishJob()
            return
        }
        CacheJob.step = 0
        CacheJob.misses = 0
        CacheJob.stepAt = System.currentTimeMillis()
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + CacheJob.queue[CacheJob.index])
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(i)
        } catch (e: Exception) {
            CacheJob.skipped++
            CacheJob.index = CacheJob.index + 1
            hnd.postDelayed({ launch() }, 300L)
        }
    }

    private fun next() {
        CacheJob.index = CacheJob.index + 1
        CacheJob.step = 0
        CacheJob.misses = 0
        CacheJob.stepAt = System.currentTimeMillis() + 1500L
        hnd.postDelayed({ launch() }, 500L)
    }

    private fun finishJob() {
        CacheJob.running = false
        hnd.removeCallbacks(watchdog)
        var r = "Cleared the cache of " + CacheJob.cleaned.toString() + " app(s)."
        if (CacheJob.skipped > 0) {
            r += "\n" + CacheJob.skipped.toString() + " app(s) skipped (page not recognised or nothing to clear)."
        }
        CacheJob.result = r
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun markDoneSoon(idx: Int, delay: Long, count: Boolean) {
        hnd.postDelayed({
            if (CacheJob.running && CacheJob.index == idx) {
                if (count) CacheJob.cleaned = CacheJob.cleaned + 1
                next()
            }
        }, delay)
    }

    private fun textOf(n: AccessibilityNodeInfo): String {
        val t: CharSequence? = n.text ?: n.contentDescription
        return if (t == null) "" else t.toString().trim().lowercase()
    }

    private fun collect(n: AccessibilityNodeInfo?, out: ArrayList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > 40 || out.size > 500) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 5) {
            if (c.isClickable) return c
            c = c.parent
            d++
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClearCache(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        for (n in nodes) {
            val t = textOf(n)
            if (t.isEmpty() || t.length > 40) continue
            if (!(t.contains("cache") || t.contains("कैश"))) continue
            var hasClear = false
            for (w in clearWords) if (t.contains(w)) hasClear = true
            if (!hasClear) continue
            val c = clickableOf(n)
            if (c != null) return c
        }
        return null
    }

    private fun findStorage(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        for (n in nodes) {
            val t = textOf(n)
            if (t.isEmpty() || t.length > 60) continue
            if (t.contains("clear")) continue
            if (t.contains("storage") || t.contains("स्टोरेज")) {
                val c = clickableOf(n)
                if (c != null) return c
            }
        }
        return null
    }

    private fun process() {
        if (!CacheJob.running) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("launcher") ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")
        ) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes, 0)
        val idx = CacheJob.index

        if (CacheJob.step == 2) {
            // after tapping Clear cache: some phones show a small confirmation
            var hasCache = false
            for (n in nodes) {
                val t = textOf(n)
                if (t.contains("cache")) hasCache = true
            }
            if (hasCache) {
                for (n in nodes) {
                    val t = textOf(n)
                    if (t == "ok" || t == "yes" || t == "confirm") {
                        if (clickNode(n)) return
                    }
                }
            }
            return
        }

        val cc = findClearCache(nodes)
        if (cc != null) {
            if (!cc.isEnabled) {
                CacheJob.step = 2
                markDoneSoon(idx, 300L, false)
                return
            }
            if (clickNode(cc)) {
                CacheJob.step = 2
                CacheJob.stepAt = System.currentTimeMillis()
                markDoneSoon(idx, 1500L, true)
            }
            return
        }

        if (CacheJob.step == 0) {
            val st = findStorage(nodes)
            if (st != null && clickNode(st)) {
                CacheJob.step = 1
                CacheJob.misses = 0
                CacheJob.stepAt = System.currentTimeMillis()
                return
            }
        }

        CacheJob.misses = CacheJob.misses + 1
        if (CacheJob.step == 1 && CacheJob.misses == 4) CacheJob.step = 0
        if (CacheJob.misses >= 2) {
            for (n in nodes) {
                if (n.isScrollable) {
                    n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    break
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ring gauge
// ─────────────────────────────────────────────────────────────────────────────

class RingView(ctx: Context) : View(ctx) {
    private var percent = 0f
    private var ringColor = Col.ACC
    private var centerText = ""
    private var label = ""
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    init {
        trackP.style = Paint.Style.STROKE
        trackP.color = Color.parseColor("#26323F")
        arcP.style = Paint.Style.STROKE
        arcP.strokeCap = Paint.Cap.ROUND
        bigP.color = Color.WHITE
        bigP.textAlign = Paint.Align.CENTER
        bigP.typeface = Typeface.DEFAULT_BOLD
        smallP.color = Col.SUB
        smallP.textAlign = Paint.Align.CENTER
    }

    fun setValue(p: Float, text: String, lab: String) {
        percent = if (p < 0f) 0f else if (p > 1f) 1f else p
        centerText = text
        label = lab
        ringColor = if (percent < 0.70f) Col.ACC else if (percent < 0.90f) Col.WARN else Col.BAD
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val hh = height.toFloat()
        val d = if (w < hh) w else hh
        val sw = d * 0.085f
        trackP.strokeWidth = sw
        arcP.strokeWidth = sw
        arcP.color = ringColor
        val cx = w / 2f
        val cy = hh / 2f
        val r = d / 2f - sw
        oval.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 0f, 360f, false, trackP)
        canvas.drawArc(oval, -90f, 360f * percent, false, arcP)
        bigP.textSize = d * 0.2f
        canvas.drawText(centerText, cx, cy + d * 0.03f, bigP)
        smallP.textSize = d * 0.095f
        canvas.drawText(label, cx, cy + d * 0.17f, smallP)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Storage scanner (one pass: junk, big files, duplicate candidates, breakdown)
// ─────────────────────────────────────────────────────────────────────────────

class Scanner(
    private val ctx: Context,
    private val root: File,
    private val installed: Set<String>,
    private val excluded: List<String>,
    val data: ScanData
) {
    @Volatile var cancel = false
    @Volatile var files = 0
    @Volatile var curPath = ""
    private var walkEmpty = false
    private val knownAds = HashSet<String>()
    private val pkgRegex = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")

    init {
        val names = arrayOf(".utsystemconfig", ".um", ".datastorage", ".tbs", ".turing", ".gs_fs0", ".gs_fs1", ".adiu", ".umeng")
        for (n in names) knownAds.add(n)
    }

    fun run() {
        walk(root, 0, false)
        if (!cancel) scanMessengers()
        if (!cancel) addOwnCache()
        for (c in data.cats) {
            c.items.sortByDescending { it.size }
            c.recalc()
        }
        data.big.sortByDescending { it.size }
        data.jpegs.sortByDescending { it.size }
        if (data.big.size > 3000) {
            data.big.subList(3000, data.big.size).clear()
        }
        data.fileCount = files
        data.finished = System.currentTimeMillis()
    }

    private fun isExcluded(path: String): Boolean {
        for (e in excluded) {
            val full = root.path + "/" + e.trim('/')
            if (path == full || path.startsWith(full + "/")) return true
        }
        return false
    }

    private fun isLink(f: File): Boolean {
        return try {
            java.nio.file.Files.isSymbolicLink(f.toPath())
        } catch (e: Exception) {
            false
        }
    }

    private fun dirCat(ln: String, depth: Int): Int {
        if (ln == ".thumbnails" || ln == ".thumbs" || ln == ".thumbcache") return C_THUMBS
        if (ln.startsWith(".trash") || ln == ".recycle" || ln == ".recyclebin" || ln == "recyclebin") return C_TRASH
        if (ln == ".cache" || ln == ".tmp" || ln == ".temp") return C_CACHEDIR
        if (ln == "cache" || ln == "caches" || ln == "tmp" || ln == "temp") return C_VCACHE
        if (ln == "log" || ln == "logs" || ln == ".log" || ln == ".logs") return C_LOGS
        if (depth == 0) {
            if (knownAds.contains(ln)) return C_ADSDK
            if (ln.startsWith(".")) return C_HIDDEN
        }
        return -1
    }

    private fun typeOf(ext: String): Int {
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng" -> 0
            "mp4", "mkv", "3gp", "avi", "mov", "webm", "m4v", "flv" -> 1
            "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr", "wma" -> 2
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "odt", "rtf", "mobi" -> 3
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> 4
            "apk", "apks", "xapk", "apkm" -> 5
            else -> 6
        }
    }

    private fun dirSize(d: File): Long {
        var total = 0L
        val stack = ArrayList<File>()
        stack.add(d)
        while (stack.isNotEmpty()) {
            if (cancel) break
            val cur = stack.removeAt(stack.size - 1)
            val kids = cur.listFiles() ?: continue
            for (k in kids) {
                if (k.isDirectory) {
                    if (!isLink(k)) stack.add(k)
                } else {
                    total += k.length()
                }
            }
        }
        return total
    }

    private fun walk(dir: File, depth: Int, inDl: Boolean): Long {
        var total = 0L
        var empty = true
        val emptyKids = ArrayList<File>()
        val kids = dir.listFiles()
        if (kids == null) {
            walkEmpty = false
            return 0L
        }
        for (f in kids) {
            if (cancel) break
            if (f.isDirectory) {
                val ln = f.name.lowercase()
                if (depth == 0 && ln == "android") {
                    empty = false
                    continue
                }
                if (isExcluded(f.path)) {
                    empty = false
                    continue
                }
                var cat = dirCat(ln, depth)
                if (cat < 0 && depth == 0 && !ln.startsWith(".") && pkgRegex.matches(ln) && !installed.contains(ln)) {
                    cat = C_RESIDUAL
                }
                if (cat >= 0) {
                    val sz = dirSize(f)
                    if (sz > 0L) {
                        data.add(cat, f.path, sz, true)
                        total += sz
                        empty = false
                        continue
                    }
                }
                if (depth >= 40 || isLink(f)) {
                    empty = false
                    continue
                }
                total += walk(f, depth + 1, inDl || (depth == 0 && (ln == "download" || ln == "downloads")))
                if (walkEmpty) emptyKids.add(f) else empty = false
            } else {
                empty = false
                files++
                if ((files and 63) == 0) curPath = f.path
                val ln = f.name.lowercase()
                val len = f.length()
                total += len
                if (ln.startsWith(".trashed-")) {
                    data.add(C_TRASH, f.path, len, false)
                    continue
                }
                if (ln.startsWith(".thumbdata")) {
                    data.add(C_THUMBS, f.path, len, false)
                    continue
                }
                if (len == 0L) {
                    if (ln != ".nomedia") data.add(C_EMPTYFILE, f.path, 0L, false)
                    continue
                }
                val dot = ln.lastIndexOf('.')
                val ext = if (dot >= 0) ln.substring(dot + 1) else ""
                var jc = -1
                when (ext) {
                    "tmp", "temp", "swp", "swo", "chk" -> jc = C_TEMP
                    "log", "dmp", "trace" -> jc = C_LOGS
                    "bak", "old", "orig", "bk" -> jc = C_BACKUP
                    "crdownload", "part", "partial" -> jc = C_PARTIAL
                    "apk", "apks", "xapk", "apkm" -> jc = C_APKS
                    else -> {}
                }
                if (jc < 0 && ln.endsWith("~")) jc = C_TEMP
                if (jc >= 0) {
                    data.add(jc, f.path, len, false)
                    if (jc == C_APKS) {
                        data.typeSize[5] += len
                        data.typeCount[5] += 1
                    }
                    continue
                }
                val t = typeOf(ext)
                if (inDl && (System.currentTimeMillis() - f.lastModified()) > 5184000000L) {
                    data.add(C_OLDDL, f.path, len, false)
                    continue
                }
                if (t == 0 && dir.name.lowercase() == "screenshots") {
                    data.add(C_SHOTS, f.path, len, false)
                    continue
                }
                data.typeSize[t] += len
                data.typeCount[t] += 1
                if (t == 0 && len >= 30720L && data.photos.size < 20000) data.photos.add(f.path)
                if ((ext == "jpg" || ext == "jpeg") && len >= 524288L && data.jpegs.size < 20000) {
                    data.jpegs.add(FItem(f.path, len, false, -4, true))
                }
                if (len >= 10L * 1024L * 1024L) {
                    data.big.add(FItem(f.path, len, false, -1, false))
                }
                if (len >= 51200L) {
                    var l = data.sizeMap[len]
                    if (l == null) {
                        l = ArrayList<String>()
                        data.sizeMap[len] = l
                    }
                    l.add(f.path)
                }
            }
        }
        if (!empty) {
            for (k in emptyKids) data.add(C_EMPTYDIR, k.path, 0L, true)
        }
        walkEmpty = empty && !cancel
        return total
    }

    private fun scanMessengers() {
        val mediaRoots = arrayOf(
            "Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp/Media",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media", "WhatsApp Business/Media",
            "Android/media/org.telegram.messenger/Telegram", "Telegram"
        )
        for (rp in mediaRoots) {
            if (cancel) return
            val base = File(root, rp)
            if (!base.isDirectory || isExcluded(base.path)) continue
            val kids = base.listFiles() ?: continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val ln = k.name.lowercase()
                val sz = dirSize(k)
                if (sz <= 0L) continue
                if (ln == ".statuses") {
                    data.add(C_STATUS, k.path, sz, true)
                } else if (!ln.startsWith(".")) {
                    data.add(C_MSGR, k.path, sz, true)
                }
            }
        }
        val dbDirs = arrayOf(
            "Android/media/com.whatsapp/WhatsApp/Databases", "WhatsApp/Databases",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Databases", "WhatsApp Business/Databases"
        )
        for (rp in dbDirs) {
            val base = File(root, rp)
            val kids = base.listFiles() ?: continue
            val list = ArrayList<File>()
            for (k in kids) {
                if (k.isFile && k.name.lowercase().startsWith("msgstore")) list.add(k)
            }
            if (list.size < 2) continue
            list.sortWith(Comparator { a, b -> b.lastModified().compareTo(a.lastModified()) })
            for (i in 1 until list.size) data.add(C_CHATBAK, list[i].path, list[i].length(), false)
        }
    }

    private fun addOwnCache() {
        val dirs = ArrayList<File>()
        dirs.add(ctx.cacheDir)
        val ext = ctx.externalCacheDir
        if (ext != null) dirs.add(ext)
        for (d in dirs) {
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                val sz = if (k.isDirectory) dirSize(k) else k.length()
                if (sz > 0L) data.add(C_APPCACHE, k.path, sz, k.isDirectory)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// List adapter (one flexible row layout used by every list in the app)
// ─────────────────────────────────────────────────────────────────────────────

class RowAdapter(private val act: Activity, private val lis: RowL) : BaseAdapter() {
    var rows: List<Row> = ArrayList<Row>()

    private class Holder(
        val root: LinearLayout,
        val arrow: TextView,
        val cb: CheckBox,
        val icon: ImageView,
        val title: TextView,
        val sub: TextView,
        val right: TextView
    )

    private fun dp(v: Int): Int {
        return (v * act.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun submitRows(r: List<Row>) {
        rows = r
        notifyDataSetChanged()
    }

    fun attach(lv: ListView) {
        lv.adapter = this
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < rows.size) lis.onRow(rows[pos])
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < rows.size) lis.onLong(rows[pos])
            true
        }
    }

    override fun getCount(): Int {
        return rows.size
    }

    override fun getItem(position: Int): Any {
        return rows[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private fun makeHolder(): Holder {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL

        val arrow = TextView(act)
        arrow.textSize = 14f
        arrow.setTextColor(Col.SUB)
        arrow.gravity = Gravity.CENTER
        root.addView(arrow, LinearLayout.LayoutParams(dp(22), WC))

        val cb = CheckBox(act)
        cb.isFocusable = false
        cb.buttonTintList = ColorStateList.valueOf(Col.ACC)
        root.addView(cb, LinearLayout.LayoutParams(WC, WC))

        val icon = ImageView(act)
        val ilp = LinearLayout.LayoutParams(dp(40), dp(40))
        ilp.setMargins(dp(4), 0, dp(10), 0)
        root.addView(icon, ilp)

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val title = TextView(act)
        title.textSize = 14f
        title.setTextColor(Col.TXT)
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.MIDDLE
        val sub = TextView(act)
        sub.textSize = 11f
        sub.setTextColor(Col.SUB)
        sub.maxLines = 2
        sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(title, LinearLayout.LayoutParams(MP, WC))
        col.addView(sub, LinearLayout.LayoutParams(MP, WC))
        root.addView(col, LinearLayout.LayoutParams(0, WC, 1f))

        val right = TextView(act)
        right.textSize = 13f
        right.setTextColor(Col.TXT)
        right.typeface = Typeface.DEFAULT_BOLD
        val rlp = LinearLayout.LayoutParams(WC, WC)
        rlp.setMargins(dp(8), 0, 0, 0)
        root.addView(right, rlp)

        return Holder(root, arrow, cb, icon, title, sub, right)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val h: Holder
        val v: View
        if (convertView == null) {
            h = makeHolder()
            v = h.root
            v.tag = h
        } else {
            v = convertView
            h = convertView.tag as Holder
        }
        bind(h, rows[position])
        return v
    }

    private fun bind(h: Holder, r: Row) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        val bgColor = when (r.type) {
            T_HEAD -> Col.CARD
            T_GROUP -> Col.CARD
            T_ITEM -> Col.CARD2
            T_APP -> Col.CARD
            else -> Color.TRANSPARENT
        }
        bg.setColor(bgColor)
        h.root.background = bg
        val padL = if (r.indentDp >= 0) r.indentDp else if (r.type == T_ITEM) 24 else 10
        h.root.setPadding(dp(padL), dp(8), dp(10), dp(8))

        h.title.text = r.title
        h.sub.text = r.sub
        h.sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        h.right.text = r.right
        h.right.setTextColor(if (r.rightColor != 0) r.rightColor else Col.TXT)

        h.arrow.visibility = if (r.type == T_HEAD) View.VISIBLE else View.GONE
        h.arrow.text = if (r.expanded) "▼" else "▶"

        h.cb.visibility = if (r.showCb) View.VISIBLE else View.GONE
        h.cb.isChecked = r.checked
        h.cb.setOnClickListener { lis.onCheck(r, h.cb.isChecked) }

        val p = r.pkg
        if (r.type == T_APP && p != null) {
            h.icon.visibility = View.VISIBLE
            try {
                h.icon.setImageDrawable(act.packageManager.getApplicationIcon(p))
            } catch (e: Exception) {
                h.icon.setImageDrawable(null)
            }
        } else {
            h.icon.setImageDrawable(null)
            h.icon.visibility = View.GONE
        }

        if (r.type == T_TEXT) {
            h.title.setSingleLine(false)
            h.title.setTextColor(Col.SUB)
            h.title.textSize = 13f
        } else {
            h.title.setSingleLine(true)
            h.title.setTextColor(Col.TXT)
            h.title.textSize = 14f
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main activity
// ─────────────────────────────────────────────────────────────────────────────

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private val rootPath: String = Environment.getExternalStorageDirectory().path

    private lateinit var content: FrameLayout
    private val tabViews = ArrayList<View>()
    private val navBtns = ArrayList<TextView>()
    private var curTab = 0

    // state
    private var scan: ScanData? = null
    private var scanner: Scanner? = null
    private var scanning = false
    private var quickAfterScan = false
    private var busyDelete = false
    private var dupGroups: ArrayList<DupGroup>? = null
    private var dupBusy = false
    private var simGroups: ArrayList<DupGroup>? = null
    private var simBusy = false
    private var shrinkQ = 80
    private var shrinkDim = 0
    private var moveItems = ArrayList<FItem>()
    private lateinit var btnFilesAct2: Button
    private lateinit var btnFilesMove: Button
    @Volatile private var simDone = 0
    @Volatile private var simTotal = 0
    @Volatile private var dupDone = 0
    @Volatile private var dupTotal = 0
    @Volatile private var dupCancel = false
    private var filesMode = 0
    private var largeMb = 50
    private val appsAll = ArrayList<AppRec>()
    private var appsBusy = false
    private var appsSort = 0
    private var appsShowSystem = false
    private var usageOk = false
    private val boostRows = ArrayList<Row>()
    private var boostBusy = false
    private var boostLoaded = false

    // home
    private lateinit var ringStorage: RingView
    private lateinit var ringRam: RingView
    private lateinit var homeStorageTv: TextView
    private lateinit var homeRamTv: TextView
    private lateinit var homePermCard: LinearLayout
    private lateinit var homeStatsTv: TextView
    private lateinit var homeTargetTv: TextView
    private lateinit var btnTarget: Button

    // junk
    private lateinit var junkList: ListView
    private lateinit var junkAdapter: RowAdapter
    private lateinit var junkInfo: TextView
    private lateinit var junkProg: TextView
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var btnClean: Button

    // apps
    private lateinit var appsList: ListView
    private lateinit var appsAdapter: RowAdapter
    private lateinit var appsInfo: TextView
    private lateinit var btnUsage: Button
    private val sortBtns = ArrayList<Button>()

    // files
    private lateinit var filesInfo: TextView
    private lateinit var btnFilesAct: Button
    private lateinit var btnFilesDel: Button
    private lateinit var storageScroll: ScrollView
    private lateinit var storageBox: LinearLayout
    private lateinit var filesList: ListView
    private lateinit var filesAdapter: RowAdapter
    private val filesTabBtns = ArrayList<Button>()

    // boost
    private lateinit var ringRam2: RingView
    private lateinit var boostInfo: TextView
    private lateinit var boostList: ListView
    private lateinit var boostAdapter: RowAdapter
    private lateinit var btnBoost: Button

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun roundBg(color: Int, r: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        return g
    }

    private fun lpm(w: Int, h: Int, weight: Float, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun mkText(s: String, sp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun mkBtn(label: String, bg: Int, fg: Int, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(fg)
        b.textSize = 14f
        b.background = roundBg(bg, 12)
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        b.setOnClickListener { action() }
        return b
    }

    private fun styleList(lv: ListView) {
        lv.divider = ColorDrawable(Color.TRANSPARENT)
        lv.dividerHeight = dp(3)
        lv.setBackgroundColor(Col.BG)
        lv.cacheColorHint = Color.TRANSPARENT
        lv.setSelector(android.R.color.transparent)
        lv.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun rel(path: String): String {
        val p = path.removePrefix(rootPath)
        return if (p.isEmpty()) "/" else p
    }

    private fun readMem(): LongArray {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return longArrayOf(mi.totalMem, mi.availMem)
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                i.data = Uri.parse("package:" + packageName)
                startActivity(i)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    toast("Open Settings > Apps > Special access > All files access")
                }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                11
            )
        }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun openUsageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings > Apps > Special access > Usage access")
        }
    }

    private fun excludedList(): ArrayList<String> {
        val out = ArrayList<String>()
        val s = prefs.getString("excluded", "") ?: ""
        for (p in s.split("\n")) {
            val t = p.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    private fun saveExcluded(list: List<String>) {
        prefs.edit().putString("excluded", list.joinToString("\n")).apply()
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cleaner", MODE_PRIVATE)
        largeMb = prefs.getInt("large_mb", 50)
        detectVolumes()

        window.setBackgroundDrawable(ColorDrawable(Col.BG))
        window.statusBarColor = Col.BG
        window.navigationBarColor = Col.CARD

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Col.BG)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MP, 0, 1f))

        tabViews.add(buildHome())
        tabViews.add(buildJunk())
        tabViews.add(buildApps())
        tabViews.add(buildFiles())
        tabViews.add(buildBoost())
        for (v in tabViews) content.addView(v, FrameLayout.LayoutParams(MP, MP))

        root.addView(buildNav(), LinearLayout.LayoutParams(MP, dp(58)))
        setContentView(root)
        showTab(0)
        try {
            val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (autoEnabled() && js.getPendingJob(1001) == null) setAuto(true)
        } catch (e: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (CacheJob.running) {
            CacheJob.running = false
            toast("Auto cache clean stopped")
        }
        refreshHome()
        val res = CacheJob.result
        if (res.isNotEmpty()) {
            CacheJob.result = ""
            showTab(2)
            loadApps()
            AlertDialog.Builder(this)
                .setTitle("Auto cache clean finished")
                .setMessage(res + "\nThe app list is refreshed with the new sizes.")
                .setPositiveButton("OK", null)
                .show()
        } else if (curTab == 2) {
            loadApps()
        }
        if (res.isEmpty()) checkGrowth()
    }

    override fun onStop() {
        super.onStop()
        // remember how full internal storage was when the app was left
        if (!growthBusy && !busyDelete && !scanning) saveBaseline()
    }

    // ── Storage growth watcher ──────────────────────────────────────────────
    // Remembers the used internal storage when you leave the app. On the next open, if the used space
    // grew (e.g. 45 GB -> 45.6 GB), it deletes new SAFE junk (temp, logs, thumbnails, hidden caches,
    // ad leftovers, status cache) up to the amount of growth. Your own files (photos, videos, downloads,
    // chats, review categories) are never touched by this.

    private var growthBusy = false

    private fun growthOn(): Boolean {
        return prefs.getBoolean("growth_on", true)
    }

    private fun usedBytes(): Long {
        return try {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            st.totalBytes - st.availableBytes
        } catch (e: Exception) {
            -1L
        }
    }

    private fun saveBaseline() {
        val u = usedBytes()
        if (u >= 0L) prefs.edit().putLong("base_used", u).apply()
    }

    private fun checkGrowth() {
        if (!growthOn() || growthBusy || busyDelete || scanning || CacheJob.running) return
        if (!hasStorageAccess()) return
        val used = usedBytes()
        if (used < 0L) return
        val base = prefs.getLong("base_used", -1L)
        if (base < 0L) {
            prefs.edit().putLong("base_used", used).apply()
            return
        }
        val growth = used - base
        if (growth < 50L * 1048576L) {
            prefs.edit().putLong("base_used", used).apply()
            return
        }
        growthBusy = true
        toast("Storage grew by " + fmt(growth) + " - cleaning new junk...")
        val inst = HashSet<String>()
        try {
            for (ai in packageManager.getInstalledApplications(0)) inst.add(ai.packageName.lowercase())
        } catch (e: Exception) {
        }
        val ex = excludedList()
        Thread(Runnable {
            var freed = 0L
            var count = 0
            val deleted = ArrayList<String>()
            try {
                val data = ScanData()
                val sc = Scanner(this, Environment.getExternalStorageDirectory(), inst, ex, data)
                sc.run()
                val cand = ArrayList<FItem>()
                for (c in data.cats) {
                    if (c.review) continue
                    for (i in c.items) cand.add(i)
                }
                // newest junk first: that is what appeared since the last time
                cand.sortWith(Comparator { a, b -> File(b.path).lastModified().compareTo(File(a.path).lastModified()) })
                val pick = ArrayList<FItem>()
                var sum = 0L
                for (fi in cand) {
                    if (sum >= growth) break
                    pick.add(fi)
                    sum += fi.size
                }
                freed = deleteFItems(pick, deleted)
                count = deleted.size
                if (deleted.isNotEmpty()) scanMedia(deleted)
            } catch (e: Exception) {
            }
            val fr = freed
            val cnt = count
            runOnUiThread {
                growthBusy = false
                val after = usedBytes()
                if (after >= 0L) prefs.edit().putLong("base_used", after).apply()
                if (fr > 0L) {
                    prefs.edit()
                        .putLong("freed_total", prefs.getLong("freed_total", 0L) + fr)
                        .putLong("last_clean", System.currentTimeMillis())
                        .apply()
                }
                scan = null
                refreshHome()
                rebuildJunk()
                if (!isFinishing) {
                    val rest = if (growth > fr) growth - fr else 0L
                    var msg = "Used storage grew by " + fmt(growth) + " since you last used the app.\n" +
                        "Deleted " + cnt.toString() + " junk item(s): " + fmt(fr) + "."
                    if (rest > 0L) {
                        msg += "\n\nThe other " + fmt(rest) + " is not junk (new photos, downloads, app data, updates...) " +
                            "so it was left alone. Use Large files / Explore to review it."
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Storage growth check")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }).start()
    }

    // ── Target size: fix a used-storage size, one tap brings it back ─────────
    // Order of work: (1) delete newest SAFE junk, (2) ask Android to drop app caches,
    // Nothing else is ever deleted: your files, folders, photos, downloads, chats and apps are never touched.

    private fun targetBytes(): Long {
        return prefs.getLong("target_used", -1L)
    }

    private fun refreshTarget() {
        val t = targetBytes()
        val used = usedBytes()
        if (t <= 0L || used < 0L) {
            homeTargetTv.text = "🎯 Target size: not set\nSet the used-storage size you want to stay at, then bring it back with one tap."
            btnTarget.text = "Set target size"
            return
        }
        val over = used - t
        homeTargetTv.text = "🎯 Target: " + fmt(t) + "   ·   Now: " + fmt(used) + "\n" +
            (if (over > 0L) "Over target by " + fmt(over) else "At or below target ✔")
        btnTarget.text = if (over > 0L) "Bring back to " + fmt(t) else "Target reached ✔ (tap to re-check)"
    }

    private fun onTargetButton() {
        if (targetBytes() <= 0L) showTargetDialog() else runTargetClean()
    }

    private fun showTargetDialog() {
        val used = usedBytes()
        val cur = targetBytes()
        val et = EditText(this)
        et.hint = "e.g. 47.35"
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        if (cur > 0L) et.setText(String.format(Locale.US, "%.2f", cur / 1073741824.0))
        AlertDialog.Builder(this)
            .setTitle("Target used storage")
            .setMessage(
                "Used internal storage right now: " + fmt(used) + " (measured the same way as the ring on the home screen, " +
                    "so it can differ slightly from Android Settings).\n\nType the size in GB to stay at:"
            )
            .setView(et)
            .setPositiveButton("Save", DialogInterface.OnClickListener { _, _ ->
                val txt = et.text.toString().trim().replace(',', '.')
                val gb = txt.toDoubleOrNull()
                if (gb == null || gb <= 0.0) {
                    toast("Enter a number like 47.35")
                } else {
                    prefs.edit().putLong("target_used", (gb * 1073741824.0).toLong()).apply()
                    refreshHome()
                    toast("Target saved: " + String.format(Locale.US, "%.2f", gb) + " GB")
                }
            })
            .setNeutralButton("Use current", DialogInterface.OnClickListener { _, _ ->
                if (used > 0L) {
                    prefs.edit().putLong("target_used", used).apply()
                    refreshHome()
                    toast("Target set to " + fmt(used))
                }
            })
            .setNegativeButton(if (cur > 0L) "Remove target" else "Cancel", DialogInterface.OnClickListener { _, _ ->
                if (cur > 0L) {
                    prefs.edit().remove("target_used").apply()
                    refreshHome()
                    toast("Target removed")
                }
            })
            .show()
    }

    private fun runTargetClean() {
        if (growthBusy || busyDelete || scanning || CacheJob.running) {
            toast("Busy - try again in a moment")
            return
        }
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        val t = targetBytes()
        val used0 = usedBytes()
        if (t <= 0L || used0 < 0L) {
            showTargetDialog()
            return
        }
        if (used0 <= t) {
            toast("Already at or below target")
            refreshHome()
            return
        }
        growthBusy = true
        toast("Cleaning back to " + fmt(t) + "...")
        val inst = HashSet<String>()
        try {
            for (ai in packageManager.getInstalledApplications(0)) inst.add(ai.packageName.lowercase())
        } catch (e: Exception) {
        }
        val ex = excludedList()
        Thread(Runnable {
            val margin = 10L * 1048576L
            var freed = 0L
            val deleted = ArrayList<String>()
            try {
                val data = ScanData()
                val sc = Scanner(this, Environment.getExternalStorageDirectory(), inst, ex, data)
                sc.run()
                val safe = ArrayList<FItem>()
                for (c in data.cats) {
                    if (!c.review) safe.addAll(c.items)
                }
                val newestFirst = Comparator<FItem> { a, b -> File(b.path).lastModified().compareTo(File(a.path).lastModified()) }
                safe.sortWith(newestFirst)

                // 1) safe junk
                var cur = usedBytes()
                if (cur > t) {
                    val need = cur - t + margin
                    val pick = ArrayList<FItem>()
                    var s = 0L
                    for (fi in safe) {
                        if (s >= need) break
                        pick.add(fi)
                        s += fi.size
                    }
                    freed += deleteFItems(pick, deleted)
                }
                // 2) Android's own app caches
                cur = usedBytes()
                if (cur > t) {
                    try {
                        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                        val uuid = sm.getUuidForPath(filesDir)
                        val want = sm.getAllocatableBytes(uuid) - 200L * 1048576L
                        val before = freeBytes()
                        if (want > before) {
                            val tmp = File(cacheDir, "trim.tmp")
                            val fos = FileOutputStream(tmp)
                            try {
                                sm.allocateBytes(fos.fd, want)
                            } finally {
                                try {
                                    fos.close()
                                } catch (e: Exception) {
                                }
                                tmp.delete()
                            }
                            val after = freeBytes()
                            if (after > before) freed += after - before
                        }
                    } catch (e: Exception) {
                    }
                }
                if (deleted.isNotEmpty()) scanMedia(deleted)
            } catch (e: Exception) {
            }
            val fr = freed
            runOnUiThread {
                growthBusy = false
                val after = usedBytes()
                if (after >= 0L) prefs.edit().putLong("base_used", after).apply()
                if (fr > 0L) {
                    prefs.edit()
                        .putLong("freed_total", prefs.getLong("freed_total", 0L) + fr)
                        .putLong("last_clean", System.currentTimeMillis())
                        .apply()
                }
                scan = null
                refreshHome()
                rebuildJunk()
                if (!isFinishing) {
                    val b = AlertDialog.Builder(this).setTitle("Target size")
                    if (after in 0L..t) {
                        b.setMessage("Back at target.\nNow " + fmt(after) + "  (target " + fmt(t) + ").\nFreed about " + fmt(fr) + ".")
                        b.setPositiveButton("OK", null)
                    } else {
                        val rest = after - t
                        var msg = "Cleaned about " + fmt(fr) + ", but storage is still " + fmt(rest) + " over the target " +
                            "(now " + fmt(after) + ").\nThe rest is not safe junk: new photos, videos, downloads, chats or app data."
                        msg += "\n\nNothing of yours was deleted. Use Large files to review it yourself."
                        b.setPositiveButton("OK", null)
                        b.setMessage(msg)
                        b.setNeutralButton("Review large files", DialogInterface.OnClickListener { _, _ ->
                            showTab(3)
                            setFilesMode(1)
                        })
                        b.setNegativeButton("Close", null)
                    }
                    b.show()
                }
            }
        }).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (curTab != 0) showTab(0) else super.onBackPressed()
    }

    private fun buildNav(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Col.CARD)
        val names = arrayOf("🏠\nHome", "🧹\nJunk", "📱\nApps", "📁\nFiles", "⚡\nBoost")
        for (i in names.indices) {
            val t = TextView(this)
            t.text = names[i]
            t.gravity = Gravity.CENTER
            t.textSize = 11f
            t.setTextColor(Col.SUB)
            t.setOnClickListener { showTab(i) }
            bar.addView(t, LinearLayout.LayoutParams(0, MP, 1f))
            navBtns.add(t)
        }
        return bar
    }

    private fun showTab(i: Int) {
        curTab = i
        for (k in tabViews.indices) {
            tabViews[k].visibility = if (k == i) View.VISIBLE else View.GONE
            navBtns[k].setTextColor(if (k == i) Col.ACC else Col.SUB)
        }
        if (i == 0) refreshHome()
        if (i == 2 && appsAll.isEmpty()) loadApps()
        if (i == 3) rebuildFiles()
        if (i == 4) {
            refreshRam()
            if (!boostLoaded) loadBoost()
        }
    }

    // ── HOME ────────────────────────────────────────────────────────────────

    private fun buildHome(): View {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(24))
        sv.addView(col, ViewGroup.LayoutParams(MP, WC))

        col.addView(mkText("Deep Cleaner", 26f, Col.TXT, true))
        col.addView(mkText("Hidden junk  ·  free space  ·  RAM boost", 13f, Col.SUB, false))

        homePermCard = LinearLayout(this)
        homePermCard.orientation = LinearLayout.VERTICAL
        homePermCard.background = roundBg(Col.CARD2, 12)
        homePermCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        homePermCard.addView(mkText("All-files access is needed to scan hidden junk on your storage.", 13f, Col.WARN, false))
        homePermCard.addView(
            mkBtn("Grant access", Col.WARN, Col.DARKTXT) { requestStorageAccess() },
            lpm(WC, WC, 0f, 0, 8, 0, 0)
        )
        col.addView(homePermCard, lpm(MP, WC, 0f, 0, 14, 0, 0))

        val rr = LinearLayout(this)
        rr.orientation = LinearLayout.HORIZONTAL
        ringStorage = RingView(this)
        ringRam = RingView(this)
        ringStorage.setOnClickListener {
            showTab(3)
            setFilesMode(4)
        }
        rr.addView(ringStorage, LinearLayout.LayoutParams(0, dp(170), 1f))
        rr.addView(ringRam, LinearLayout.LayoutParams(0, dp(170), 1f))
        col.addView(rr, lpm(MP, WC, 0f, 0, 14, 0, 0))

        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        homeStorageTv = mkText("", 12f, Col.SUB, false)
        homeStorageTv.gravity = Gravity.CENTER
        homeRamTv = mkText("", 12f, Col.SUB, false)
        homeRamTv.gravity = Gravity.CENTER
        tr.addView(homeStorageTv, LinearLayout.LayoutParams(0, WC, 1f))
        tr.addView(homeRamTv, LinearLayout.LayoutParams(0, WC, 1f))
        col.addView(tr, lpm(MP, WC, 0f, 0, 4, 0, 0))

        // Target size card: fix a used-storage size, then one tap brings it back
        val tcard = LinearLayout(this)
        tcard.orientation = LinearLayout.VERTICAL
        tcard.background = roundBg(Col.CARD2, 12)
        tcard.setPadding(dp(14), dp(12), dp(14), dp(12))
        homeTargetTv = mkText("", 13f, Col.TXT, false)
        tcard.addView(homeTargetTv)
        btnTarget = mkBtn("Set target size", Col.ACC, Col.DARKTXT) { onTargetButton() }
        tcard.addView(btnTarget, lpm(MP, WC, 0f, 0, 8, 0, 0))
        tcard.addView(
            mkBtn("Change target", Col.CARD, Col.SUB) { showTargetDialog() },
            lpm(MP, WC, 0f, 0, 6, 0, 0)
        )
        col.addView(tcard, lpm(MP, WC, 0f, 0, 14, 0, 0))

        col.addView(
            mkBtn("⚡  Analyze & Quick Clean", Col.ACC, Col.DARKTXT) { startScan(true) },
            lpm(MP, WC, 0f, 0, 12, 0, 0)
        )

        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(mkBtn("🚀 Boost RAM", Col.CARD2, Col.TXT) { showTab(4) }, lpm(0, WC, 1f, 0, 0, 4, 0))
        r1.addView(mkBtn("📱 App caches", Col.CARD2, Col.TXT) { showTab(2) }, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(r1, lpm(MP, WC, 0f, 0, 10, 0, 0))

        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(mkBtn("📦 Large files", Col.CARD2, Col.TXT) { showTab(3); setFilesMode(1) }, lpm(0, WC, 1f, 0, 0, 4, 0))
        r2.addView(mkBtn("👯 Duplicates", Col.CARD2, Col.TXT) { showTab(3); setFilesMode(2) }, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(r2, lpm(MP, WC, 0f, 0, 8, 0, 0))

        val r3 = LinearLayout(this)
        r3.orientation = LinearLayout.HORIZONTAL
        r3.addView(mkBtn("🖼 Similar photos", Col.CARD2, Col.TXT) { showTab(3); setFilesMode(3) }, lpm(0, WC, 1f, 0, 0, 4, 0))
        r3.addView(mkBtn("🗂 Browse storage", Col.CARD2, Col.TXT) { showTab(3); setFilesMode(4) }, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(r3, lpm(MP, WC, 0f, 0, 8, 0, 0))

        val r4 = LinearLayout(this)
        r4.orientation = LinearLayout.HORIZONTAL
        r4.addView(mkBtn("🧽 Trim app caches", Col.CARD2, Col.TXT) { trimSystemCaches() }, lpm(0, WC, 1f, 0, 0, 4, 0))
        r4.addView(mkBtn("🗜 Shrink photos", Col.CARD2, Col.TXT) { showTab(3); setFilesMode(5) }, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(r4, lpm(MP, WC, 0f, 0, 8, 0, 0))

        col.addView(
            mkBtn("⚙  Settings & info", Col.CARD, Col.SUB) { showSettings() },
            lpm(MP, WC, 0f, 0, 8, 0, 0)
        )

        homeStatsTv = mkText("", 12f, Col.SUB, false)
        homeStatsTv.gravity = Gravity.CENTER
        col.addView(homeStatsTv, lpm(MP, WC, 0f, 0, 16, 0, 0))
        return sv
    }

    private fun refreshRam() {
        val m = readMem()
        val used = m[0] - m[1]
        val frac = if (m[0] > 0L) used.toFloat() / m[0].toFloat() else 0f
        val pct = Math.round(frac * 100f)
        ringRam.setValue(frac, pct.toString() + "%", "RAM used")
        ringRam2.setValue(frac, pct.toString() + "%", "RAM used")
        homeRamTv.text = "Free " + fmt(m[1]) + " of " + fmt(m[0])
        boostInfo.text = "Free RAM: " + fmt(m[1]) + " of " + fmt(m[0])
    }

    private fun refreshHome() {
        homePermCard.visibility = if (hasStorageAccess()) View.GONE else View.VISIBLE
        try {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            val total = st.totalBytes
            val free = st.availableBytes
            val used = total - free
            val frac = if (total > 0L) used.toFloat() / total.toFloat() else 0f
            ringStorage.setValue(frac, Math.round(frac * 100f).toString() + "%", "Storage used")
            homeStorageTv.text = "Free " + fmt(free) + " of " + fmt(total) + "\nTap the ring to explore"
        } catch (e: Exception) {
            homeStorageTv.text = "Storage info unavailable"
        }
        refreshRam()
        val freed = prefs.getLong("freed_total", 0L)
        val last = prefs.getLong("last_clean", 0L)
        var s = "Freed so far: " + fmt(freed)
        if (last > 0L) {
            s += "\nLast clean: " + java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(java.util.Date(last))
        }
        val d = scan
        if (d != null) {
            var junk = 0L
            for (c in d.cats) junk += c.total
            s += "\nLast scan found " + fmt(junk) + " of junk"
        }
        if (autoEnabled()) {
            s += "\nAuto-clean: ON (weekly, while charging, safe categories only)"
        }
        homeStatsTv.text = s
        refreshTarget()
    }

    // ── JUNK TAB ────────────────────────────────────────────────────────────

    private fun buildJunk(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(12), dp(12), dp(8))

        col.addView(mkText("Junk cleaner", 20f, Col.TXT, true))
        junkInfo = mkText("No scan yet. Tap Scan to find hidden junk.", 13f, Col.SUB, false)
        col.addView(junkInfo, lpm(MP, WC, 0f, 0, 4, 0, 6))

        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        btnScan = mkBtn("Scan storage", Col.ACC, Col.DARKTXT) { startScan(false) }
        btnStop = mkBtn("Stop", Col.CARD2, Col.TXT) { stopScan() }
        btnStop.isEnabled = false
        br.addView(btnScan, lpm(0, WC, 1f, 0, 0, 4, 0))
        br.addView(btnStop, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(br)

        junkProg = mkText("", 11f, Col.SUB, false)
        junkProg.maxLines = 2
        junkProg.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(junkProg, lpm(MP, WC, 0f, 0, 6, 0, 6))

        junkList = ListView(this)
        styleList(junkList)
        junkAdapter = RowAdapter(this, object : RowL() {
            override fun onRow(r: Row) {
                val c = r.cat
                val fi = r.item
                if (r.type == T_HEAD && c != null) {
                    c.expanded = !c.expanded
                    rebuildJunk()
                } else if (r.type == T_ITEM && fi != null) {
                    fi.checked = !fi.checked
                    rebuildJunk()
                }
            }

            override fun onLong(r: Row) {
                val fi = r.item
                if (fi != null) showPath(fi)
            }

            override fun onCheck(r: Row, checked: Boolean) {
                val c = r.cat
                val fi = r.item
                if (r.type == T_HEAD && c != null) {
                    for (x in c.items) x.checked = checked
                } else if (fi != null) {
                    fi.checked = checked
                }
                rebuildJunk()
            }
        })
        junkAdapter.attach(junkList)
        col.addView(junkList, LinearLayout.LayoutParams(MP, 0, 1f))

        btnClean = mkBtn("Clean selected", Col.ACC, Col.DARKTXT) { confirmClean(selectedJunk()) }
        btnClean.isEnabled = false
        col.addView(btnClean, lpm(MP, WC, 0f, 0, 8, 0, 0))
        rebuildJunk()
        return col
    }

    private fun showPath(fi: FItem) {
        AlertDialog.Builder(this)
            .setTitle(File(fi.path).name)
            .setMessage(fi.path + "\n\n" + fmt(fi.size) + (if (fi.isDir) " (folder)" else ""))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun selectedJunk(): ArrayList<FItem> {
        val out = ArrayList<FItem>()
        val d = scan ?: return out
        for (c in d.cats) for (i in c.items) if (i.checked) out.add(i)
        return out
    }

    private fun rebuildJunk() {
        val rows = ArrayList<Row>()
        val d = scan
        var found = 0L
        var safe = 0L
        var sel = 0L
        if (d == null) {
            val r = Row(T_TEXT)
            r.title = "Scan your storage to see what can be cleaned."
            rows.add(r)
        } else {
            val ordered = ArrayList<JCat>()
            for (c in d.cats) if (!c.review) ordered.add(c)
            for (c in d.cats) if (c.review) ordered.add(c)
            for (c in ordered) {
                if (c.items.isEmpty()) continue
                found += c.total
                if (!c.review) safe += c.total
                sel += c.selSize()
                val hr = Row(T_HEAD)
                hr.title = c.title
                hr.sub = c.desc + "  ·  " + c.items.size.toString() + " item(s)"
                hr.right = fmt(c.total)
                hr.rightColor = if (c.review) Col.WARN else Col.ACC
                hr.showCb = true
                hr.checked = c.selCount() == c.items.size
                hr.expanded = c.expanded
                hr.cat = c
                rows.add(hr)
                if (c.expanded) {
                    val n = if (c.items.size < 300) c.items.size else 300
                    for (k in 0 until n) rows.add(itemRow(c.items[k]))
                    if (c.items.size > n) {
                        val tr = Row(T_TEXT)
                        tr.title = "... and " + (c.items.size - n).toString() + " more (they follow the category checkbox)"
                        rows.add(tr)
                    }
                }
            }
            if (found == 0L && d.cats[C_EMPTYDIR].items.isEmpty()) {
                val r = Row(T_TEXT)
                r.title = "No junk found. Your storage is clean."
                rows.add(r)
            }
        }
        junkAdapter.submitRows(rows)
        if (d != null) {
            junkInfo.text = "Found " + fmt(found) + " (safe " + fmt(safe) + ")  ·  Selected " + fmt(sel)
        }
        btnClean.text = "Clean selected  ·  " + fmt(sel)
        btnClean.isEnabled = sel > 0L && !scanning && !busyDelete
    }

    private fun itemRow(fi: FItem): Row {
        val r = Row(T_ITEM)
        val f = File(fi.path)
        r.title = f.name + (if (fi.isDir) "/" else "")
        r.sub = rel(f.parent ?: "")
        r.right = fmt(fi.size)
        r.showCb = true
        r.checked = fi.checked
        r.item = fi
        return r
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = scanner
            if (scanning && s != null) {
                junkProg.text = "Scanning...  " + s.files.toString() + " files\n" + s.curPath
                ui.postDelayed(this, 250)
            }
        }
    }

    private fun stopScan() {
        val s = scanner
        if (s != null) s.cancel = true
        junkProg.text = "Stopping..."
    }

    private fun startScan(quick: Boolean) {
        if (scanning || busyDelete) return
        if (!hasStorageAccess()) {
            toast("Grant all-files access first")
            requestStorageAccess()
            return
        }
        scanning = true
        quickAfterScan = quick
        dupGroups = null
        showTab(1)
        btnScan.isEnabled = false
        btnStop.isEnabled = true
        btnClean.isEnabled = false
        junkInfo.text = "Scanning storage..."
        val data = ScanData()
        val inst = HashSet<String>()
        try {
            for (ai in packageManager.getInstalledApplications(0)) inst.add(ai.packageName.lowercase())
        } catch (e: Exception) {
        }
        val sc = Scanner(this, Environment.getExternalStorageDirectory(), inst, excludedList(), data)
        scanner = sc
        ui.post(ticker)
        Thread(Runnable {
            try {
                sc.run()
            } catch (e: Exception) {
            }
            runOnUiThread {
                scanning = false
                scanner = null
                btnScan.isEnabled = true
                btnStop.isEnabled = false
                if (sc.cancel) {
                    junkProg.text = "Scan stopped (partial results)"
                } else {
                    junkProg.text = "Scan complete: " + sc.files.toString() + " files checked"
                }
                scan = data
                rebuildJunk()
                rebuildFiles()
                refreshHome()
                if (quickAfterScan) {
                    quickAfterScan = false
                    confirmClean(selectedJunk())
                }
            }
        }).start()
    }

    private fun confirmClean(items: ArrayList<FItem>) {
        if (items.isEmpty()) {
            toast("Nothing selected")
            return
        }
        var total = 0L
        var folders = 0
        for (i in items) {
            total += i.size
            if (i.isDir) folders++
        }
        var msg = items.size.toString() + " item(s), " + fmt(total) + "."
        if (folders > 0) msg += "\n" + folders.toString() + " folder(s) will be deleted together with everything inside."
        msg += "\nThis cannot be undone."
        AlertDialog.Builder(this)
            .setTitle("Delete permanently?")
            .setMessage(msg)
            .setPositiveButton("Delete", DialogInterface.OnClickListener { _, _ -> doDelete(items) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanMedia(paths: List<String>) {
        var i = 0
        while (i < paths.size) {
            val end = if (paths.size < i + 400) paths.size else i + 400
            val arr = paths.subList(i, end).toTypedArray()
            try {
                MediaScannerConnection.scanFile(this, arr, null, null)
            } catch (e: Exception) {
            }
            i = end
        }
    }

    private fun doDelete(items: List<FItem>) {
        if (busyDelete) return
        busyDelete = true
        btnClean.isEnabled = false
        btnFilesDel.isEnabled = false
        junkProg.text = "Cleaning..."
        toast("Cleaning...")
        val copy = ArrayList<FItem>(items)
        Thread(Runnable {
            val deleted = ArrayList<String>()
            val freed = deleteFItems(copy, deleted)
            val ok = deleted.size
            scanMedia(deleted)
            runOnUiThread {
                busyDelete = false
                btnFilesDel.isEnabled = true
                if (freed > 0L) {
                    prefs.edit()
                        .putLong("freed_total", prefs.getLong("freed_total", 0L) + freed)
                        .putLong("last_clean", System.currentTimeMillis())
                        .apply()
                }
                pruneModel(HashSet<String>(deleted))
                junkProg.text = "Cleaned " + ok.toString() + " of " + copy.size.toString() + " item(s)"
                rebuildJunk()
                rebuildFiles()
                refreshHome()
                val failed = copy.size - ok
                var msg = "Freed " + fmt(freed)
                if (failed > 0) msg += "\n" + failed.toString() + " item(s) could not be deleted (in use or protected)."
                AlertDialog.Builder(this)
                    .setTitle("Done")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }).start()
    }

    private fun pruneGroups(g: ArrayList<DupGroup>?, gone: Set<String>) {
        if (g == null) return
        val gi = g.iterator()
        while (gi.hasNext()) {
            val grp = gi.next()
            val fi = grp.files.iterator()
            while (fi.hasNext()) {
                if (gone.contains(fi.next().path)) fi.remove()
            }
            if (grp.files.size < 2) gi.remove()
        }
    }

    private fun pruneModel(gone: Set<String>) {
        val d = scan
        if (d != null) {
            for (c in d.cats) {
                val it1 = c.items.iterator()
                while (it1.hasNext()) {
                    if (gone.contains(it1.next().path)) it1.remove()
                }
                c.recalc()
            }
            val it2 = d.big.iterator()
            while (it2.hasNext()) {
                if (gone.contains(it2.next().path)) it2.remove()
            }
            val it3 = d.photos.iterator()
            while (it3.hasNext()) {
                if (gone.contains(it3.next())) it3.remove()
            }
            val it4 = d.jpegs.iterator()
            while (it4.hasNext()) {
                if (gone.contains(it4.next().path)) it4.remove()
            }
        }
        pruneGroups(dupGroups, gone)
        pruneGroups(simGroups, gone)
        pruneTreeRoot(gone)
    }

    // ── APPS TAB ────────────────────────────────────────────────────────────

    private fun buildApps(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(12), dp(12), dp(8))

        col.addView(mkText("App caches & usage", 20f, Col.TXT, true))
        appsInfo = mkText("", 12f, Col.SUB, false)
        col.addView(appsInfo, lpm(MP, WC, 0f, 0, 4, 0, 6))

        btnUsage = mkBtn("Grant usage access (needed for sizes)", Col.WARN, Col.DARKTXT) { openUsageSettings() }
        col.addView(btnUsage, lpm(MP, WC, 0f, 0, 0, 0, 6))

        val ar = LinearLayout(this)
        ar.orientation = LinearLayout.HORIZONTAL
        ar.addView(mkBtn("Clean biggest cache", Col.ACC, Col.DARKTXT) { openBiggestCache() }, lpm(0, WC, 1f, 0, 0, 4, 0))
        ar.addView(mkBtn("Storage settings", Col.CARD2, Col.TXT) { openStorageSettings() }, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(ar)
        col.addView(
            mkBtn("🤖  Auto-clean ALL app caches", Col.ACC, Col.DARKTXT) { startAutoCache() },
            lpm(MP, WC, 0f, 0, 6, 0, 0)
        )
        col.addView(
            mkBtn("🧽  Ask Android to trim app caches", Col.CARD2, Col.TXT) { trimSystemCaches() },
            lpm(MP, WC, 0f, 0, 6, 0, 0)
        )

        val sr = LinearLayout(this)
        sr.orientation = LinearLayout.HORIZONTAL
        val labels = arrayOf("Cache", "Size", "Unused", "User apps")
        for (i in labels.indices) {
            val b = mkBtn(labels[i], Col.CARD2, Col.TXT) { onAppsTopBtn(i) }
            sr.addView(b, lpm(0, WC, 1f, 2, 0, 2, 0))
            sortBtns.add(b)
        }
        col.addView(sr, lpm(MP, WC, 0f, 0, 8, 0, 6))

        appsList = ListView(this)
        styleList(appsList)
        appsAdapter = RowAdapter(this, object : RowL() {
            override fun onRow(r: Row) {
                val a = r.app
                if (a != null) appDialog(a)
            }
        })
        appsAdapter.attach(appsList)
        col.addView(appsList, LinearLayout.LayoutParams(MP, 0, 1f))
        return col
    }

    private fun onAppsTopBtn(i: Int) {
        if (i < 3) {
            appsSort = i
        } else {
            appsShowSystem = !appsShowSystem
        }
        rebuildApps()
    }

    private fun openStorageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("Cannot open settings")
            }
        }
    }

    private fun openAppInfo(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + pkg)
            startActivity(i)
        } catch (e: Exception) {
            toast("Cannot open app info")
        }
    }

    private fun openBiggestCache() {
        if (!usageOk) {
            toast("Grant usage access first")
            openUsageSettings()
            return
        }
        var best: AppRec? = null
        for (a in appsAll) {
            if (a.cache > 0L && a.pkg != packageName) {
                val b = best
                if (b == null || a.cache > b.cache) best = a
            }
        }
        val b2 = best
        if (b2 == null || b2.cache < 100L * 1024L) {
            toast("No app has a noticeable cache")
            return
        }
        toast(b2.label + ": Storage > Clear cache (then press back)")
        openAppInfo(b2.pkg)
    }

    private fun appDialog(a: AppRec) {
        val names = ArrayList<String>()
        names.add("Open app info (Storage > Clear cache)")
        names.add("Stop background processes")
        if (!a.system) names.add("Uninstall")
        val arr = names.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setItems(arr, DialogInterface.OnClickListener { _, which ->
                if (which == 0) {
                    openAppInfo(a.pkg)
                } else if (which == 1) {
                    try {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        am.killBackgroundProcesses(a.pkg)
                        toast("Background processes stopped")
                    } catch (e: Exception) {
                        toast("Not allowed")
                    }
                } else {
                    try {
                        val i = Intent(Intent.ACTION_DELETE)
                        i.data = Uri.parse("package:" + a.pkg)
                        startActivity(i)
                    } catch (e: Exception) {
                        toast("Cannot uninstall")
                    }
                }
            })
            .show()
    }

    private fun loadApps() {
        if (appsBusy) return
        appsBusy = true
        appsInfo.text = "Loading apps..."
        Thread(Runnable {
            val pm = packageManager
            val usage = hasUsageAccess()
            val out = ArrayList<AppRec>()
            val lastMap = HashMap<String, Long>()
            var ssm0: StorageStatsManager? = null
            try {
                if (usage) {
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val now = System.currentTimeMillis()
                    val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, now - 365L * 86400000L, now)
                    if (list != null) {
                        for (u in list) {
                            val old = lastMap[u.packageName]
                            if (old == null || u.lastTimeUsed > old) lastMap[u.packageName] = u.lastTimeUsed
                        }
                    }
                    ssm0 = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
                }
            } catch (e: Exception) {
            }
            val ssm = ssm0
            val apps: List<ApplicationInfo> = try {
                pm.getInstalledApplications(0)
            } catch (e: Exception) {
                ArrayList<ApplicationInfo>()
            }
            for (ai in apps) {
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val rec = AppRec(ai.packageName, pm.getApplicationLabel(ai).toString(), sys)
                rec.lastUsed = lastMap[ai.packageName] ?: 0L
                if (ssm != null) {
                    try {
                        val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, android.os.Process.myUserHandle())
                        rec.cache = st.cacheBytes
                        rec.total = st.appBytes + st.dataBytes
                    } catch (e: Exception) {
                    }
                }
                out.add(rec)
            }
            runOnUiThread {
                appsAll.clear()
                appsAll.addAll(out)
                usageOk = usage
                appsBusy = false
                rebuildApps()
            }
        }).start()
    }

    private fun rebuildApps() {
        btnUsage.visibility = if (usageOk) View.GONE else View.VISIBLE
        val list = ArrayList<AppRec>()
        var cacheSum = 0L
        for (a in appsAll) {
            if (a.system && !appsShowSystem) continue
            list.add(a)
            if (a.cache > 0L) cacheSum += a.cache
        }
        if (appsSort == 0) {
            list.sortWith(Comparator { a, b -> b.cache.compareTo(a.cache) })
        } else if (appsSort == 1) {
            list.sortWith(Comparator { a, b -> b.total.compareTo(a.total) })
        } else {
            list.sortWith(Comparator { a, b -> a.lastUsed.compareTo(b.lastUsed) })
        }
        val rows = ArrayList<Row>()
        for (a in list) {
            val r = Row(T_APP)
            r.pkg = a.pkg
            r.app = a
            r.title = a.label
            var s = if (a.cache >= 0L) {
                "Cache " + fmt(a.cache) + "  ·  Total " + fmt(a.total)
            } else {
                "Size unknown"
            }
            if (usageOk) {
                s += "  ·  " + (if (a.lastUsed > 0L) ago(a.lastUsed) else "not used in a year")
            }
            r.sub = s
            r.right = if (a.cache >= 0L) fmt(a.cache) else ""
            r.rightColor = if (a.cache > 50L * 1024L * 1024L) Col.BAD else if (a.cache > 10L * 1024L * 1024L) Col.WARN else Col.TXT
            rows.add(r)
        }
        appsAdapter.submitRows(rows)
        appsInfo.text = list.size.toString() + " apps" + (if (usageOk) "  ·  total cache " + fmt(cacheSum) else "") +
            "\nTip: Auto-clean ALL app caches needs the helper switched on in Accessibility settings."
        val names = arrayOf("Cache", "Size", "Unused", if (appsShowSystem) "All apps" else "User apps")
        for (i in sortBtns.indices) {
            sortBtns[i].text = names[i]
            val on = (i < 3 && i == appsSort) || (i == 3 && appsShowSystem)
            sortBtns[i].background = roundBg(if (on) Col.ACC else Col.CARD2, 12)
            sortBtns[i].setTextColor(if (on) Col.DARKTXT else Col.TXT)
        }
    }

    // ── FILES TAB (storage breakdown / large files / duplicates) ────────────

    private fun buildFiles(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(12), dp(12), dp(8))

        col.addView(mkText("Space finder", 20f, Col.TXT, true))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        val labels = arrayOf("Storage", "Large", "Dupes", "Similar", "Explore", "Shrink")
        for (i in labels.indices) {
            val b = mkBtn(labels[i], Col.CARD2, Col.TXT) { setFilesMode(i) }
            b.textSize = 12f
            tr.addView(b, lpm(WC, WC, 0f, 2, 0, 2, 0))
            filesTabBtns.add(b)
        }
        hs.addView(tr)
        col.addView(hs, lpm(MP, WC, 0f, 0, 8, 0, 6))

        filesInfo = mkText("", 12f, Col.SUB, false)
        col.addView(filesInfo, lpm(MP, WC, 0f, 0, 0, 0, 6))

        val actRow = LinearLayout(this)
        actRow.orientation = LinearLayout.HORIZONTAL
        btnFilesAct = mkBtn("", Col.CARD2, Col.TXT) { onFilesAct() }
        btnFilesAct2 = mkBtn("", Col.CARD2, Col.TXT) { onFilesAct2() }
        actRow.addView(btnFilesAct, lpm(0, WC, 1f, 0, 0, 3, 0))
        actRow.addView(btnFilesAct2, lpm(0, WC, 1f, 3, 0, 0, 0))
        col.addView(actRow, lpm(MP, WC, 0f, 0, 0, 0, 6))

        val fl = FrameLayout(this)
        storageBox = LinearLayout(this)
        storageBox.orientation = LinearLayout.VERTICAL
        storageBox.setPadding(dp(4), dp(4), dp(4), dp(12))
        storageScroll = ScrollView(this)
        storageScroll.addView(storageBox, ViewGroup.LayoutParams(MP, WC))
        fl.addView(storageScroll, FrameLayout.LayoutParams(MP, MP))

        filesList = ListView(this)
        styleList(filesList)
        filesAdapter = RowAdapter(this, object : RowL() {
            override fun onRow(r: Row) {
                val grp = r.group
                val fi = r.item
                if (filesMode == 4) {
                    treeRowClick(r)
                    return
                }
                if (grp != null) {
                    var any = false
                    for (k in 1 until grp.files.size) if (grp.files[k].checked) any = true
                    for (k in grp.files.indices) grp.files[k].checked = if (k == 0) false else !any
                    rebuildFiles()
                } else if (fi != null) {
                    fi.checked = !fi.checked
                    rebuildFiles()
                }
            }

            override fun onLong(r: Row) {
                if (filesMode == 4) {
                    treeRowLong(r)
                    return
                }
                val fi = r.item
                if (fi != null) showPath(fi)
            }

            override fun onCheck(r: Row, checked: Boolean) {
                if (filesMode == 4) {
                    treeRowCheck(r, checked)
                    return
                }
                val fi = r.item
                if (fi != null) {
                    fi.checked = checked
                    rebuildFiles()
                }
            }
        })
        filesAdapter.attach(filesList)
        fl.addView(filesList, FrameLayout.LayoutParams(MP, MP))
        col.addView(fl, LinearLayout.LayoutParams(MP, 0, 1f))

        btnFilesMove = mkBtn("", Col.ACC, Col.DARKTXT) { onFilesMove() }
        col.addView(btnFilesMove, lpm(MP, WC, 0f, 0, 8, 0, 0))
        btnFilesDel = mkBtn("Delete selected", Col.BAD, Color.WHITE) { deleteFilesSelected() }
        col.addView(btnFilesDel, lpm(MP, WC, 0f, 0, 6, 0, 0))
        return col
    }

    private fun setFilesMode(m: Int) {
        filesMode = m
        rebuildFiles()
    }

    private fun onFilesAct() {
        if (filesMode == 4) {
            treeSort = (treeSort + 1) % 3
            val tr = treeRoot
            if (tr != null) sortNodes(tr.children)
            renderTree()
            return
        }
        val d = scan
        if (d == null) {
            startScan(false)
            return
        }
        if (filesMode == 1) {
            val opts = intArrayOf(10, 20, 50, 100, 250, 500, 1000)
            var idx = 0
            for (k in opts.indices) if (opts[k] == largeMb) idx = k
            largeMb = opts[(idx + 1) % opts.size]
            prefs.edit().putInt("large_mb", largeMb).apply()
            rebuildFiles()
        } else if (filesMode == 2) {
            if (dupBusy) {
                dupCancel = true
            } else {
                findDuplicates()
            }
        } else if (filesMode == 3) {
            if (simBusy) {
                dupCancel = true
            } else {
                findSimilar()
            }
        } else if (filesMode == 5) {
            val opts = intArrayOf(90, 85, 80, 70, 60)
            var idx = 0
            for (k in opts.indices) if (opts[k] == shrinkQ) idx = k
            shrinkQ = opts[(idx + 1) % opts.size]
            rebuildFiles()
        }
    }

    private fun rebuildFiles() {
        for (i in filesTabBtns.indices) {
            val on = i == filesMode
            filesTabBtns[i].background = roundBg(if (on) Col.ACC else Col.CARD2, 12)
            filesTabBtns[i].setTextColor(if (on) Col.DARKTXT else Col.TXT)
        }
        val d = scan
        storageScroll.visibility = if (filesMode == 0) View.VISIBLE else View.GONE
        filesList.visibility = if (filesMode == 0) View.GONE else View.VISIBLE
        btnFilesDel.visibility = if (filesMode == 1 || filesMode == 2 || filesMode == 3 || filesMode == 4) View.VISIBLE else View.GONE
        btnFilesMove.visibility = if (filesMode == 1 || filesMode == 5) View.VISIBLE else View.GONE
        btnFilesAct2.visibility = if (filesMode == 5 || filesMode == 4) View.VISIBLE else View.GONE
        if (filesMode == 4) {
            if (!hasStorageAccess()) {
                filesInfo.text = "Grant all-files access on the Home tab first."
                filesAdapter.submitRows(ArrayList<Row>())
                btnFilesAct.visibility = View.GONE
                btnFilesAct2.visibility = View.GONE
                btnFilesDel.isEnabled = false
                return
            }
            btnFilesAct.visibility = View.VISIBLE
            if (treeRoot == null) {
                detectVolumes()
                startTree()
            } else {
                renderTree()
            }
            return
        }
        if (d == null) {
            filesInfo.text = "No scan data yet."
            btnFilesAct.visibility = View.VISIBLE
            btnFilesAct2.visibility = View.GONE
            btnFilesAct.text = "Scan storage now"
            storageBox.removeAllViews()
            filesAdapter.submitRows(ArrayList<Row>())
            btnFilesDel.isEnabled = false
            btnFilesMove.isEnabled = false
            return
        }
        if (filesMode == 0) {
            btnFilesAct.visibility = View.GONE
            buildStorageBox(d)
        } else if (filesMode == 1) {
            rebuildLarge(d)
        } else if (filesMode == 5) {
            rebuildShrink(d)
        } else {
            rebuildGroups(filesMode == 3)
        }
    }

    private fun buildStorageBox(d: ScanData) {
        storageBox.removeAllViews()
        var sum = 0L
        for (t in 0 until 7) sum += d.typeSize[t]
        filesInfo.text = d.fileCount.toString() + " files scanned  ·  " + fmt(sum) + " of normal files (junk excluded)"
        val names = arrayOf("Images", "Videos", "Audio", "Documents", "Archives", "Installers (APK)", "Other")
        val colors = arrayOf("#19C37D", "#4DA3FF", "#F5A623", "#B388FF", "#FF7A59", "#FF5C5C", "#8FA0B3")
        var max = 0L
        for (t in 0 until 7) if (d.typeSize[t] > max) max = d.typeSize[t]
        for (t in 0 until 7) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.addView(mkText(names[t], 14f, Col.TXT, true), LinearLayout.LayoutParams(0, WC, 1f))
            line.addView(mkText(fmt(d.typeSize[t]) + "  (" + d.typeCount[t].toString() + ")", 13f, Col.SUB, false))
            storageBox.addView(line, lpm(MP, WC, 0f, 0, 12, 0, 4))
            val bar = LinearLayout(this)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.background = roundBg(Col.CARD2, 5)
            val frac = if (max > 0L) d.typeSize[t].toFloat() / max.toFloat() else 0f
            val fill = View(this)
            fill.background = roundBg(Color.parseColor(colors[t]), 5)
            bar.addView(fill, LinearLayout.LayoutParams(0, dp(10), frac))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f - frac))
            storageBox.addView(bar, LinearLayout.LayoutParams(MP, dp(10)))
        }
        var junk = 0L
        for (c in d.cats) junk += c.total
        storageBox.addView(
            mkText("Junk found in last scan: " + fmt(junk), 13f, Col.WARN, true),
            lpm(MP, WC, 0f, 0, 20, 0, 0)
        )
    }

    private fun rebuildLarge(d: ScanData) {
        val minB = largeMb.toLong() * 1048576L
        val rows = ArrayList<Row>()
        var n = 0
        var tot = 0L
        var sel = 0L
        for (fi in d.big) {
            if (fi.size < minB) continue
            n++
            tot += fi.size
            if (fi.checked) sel += fi.size
            if (rows.size < 500) {
                val ir = itemRow(fi)
                ir.sub = ir.sub + "  ·  " + java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(java.util.Date(File(fi.path).lastModified()))
                rows.add(ir)
            }
        }
        if (rows.isEmpty()) {
            val r = Row(T_TEXT)
            r.title = "No files of " + largeMb.toString() + " MB or more."
            rows.add(r)
        }
        filesAdapter.submitRows(rows)
        filesInfo.text = n.toString() + " files >= " + largeMb.toString() + " MB  ·  " + fmt(tot) + "  (tap to select, long-press for path)"
        btnFilesAct.visibility = View.VISIBLE
        btnFilesAct.text = "Minimum size: " + largeMb.toString() + " MB  (tap to change)"
        btnFilesDel.text = "Delete selected  ·  " + fmt(sel)
        btnFilesDel.isEnabled = sel > 0L && !busyDelete
        btnFilesMove.text = "Move selected to SD card / other storage  ·  " + fmt(sel)
        btnFilesMove.isEnabled = sel > 0L && !busyDelete
    }

    private fun rebuildGroups(similar: Boolean) {
        btnFilesAct.visibility = View.VISIBLE
        val g = if (similar) simGroups else dupGroups
        val busy = if (similar) simBusy else dupBusy
        val rows = ArrayList<Row>()
        var sel = 0L
        if (busy) {
            btnFilesAct.text = "Stop"
            val done = if (similar) simDone else dupDone
            val total = if (similar) simTotal else dupTotal
            filesInfo.text = (if (similar) "Analysing photos... " else "Comparing files... ") + done.toString() + " / " + total.toString()
        } else if (g == null) {
            btnFilesAct.text = if (similar) "Find similar photos" else "Find duplicate files"
            filesInfo.text = if (similar) {
                "Finds look-alike photos (burst shots, edits, resized copies). Nothing is pre-selected."
            } else {
                "Finds identical files (50 KB or larger) by content. The oldest copy of each is kept."
            }
            val r = Row(T_TEXT)
            r.title = "Tap the button above to start. This can take a while on big storage."
            rows.add(r)
        } else {
            btnFilesAct.text = "Search again"
            var wasted = 0L
            for (grp in g) {
                wasted += grp.waste
                for (fi in grp.files) if (fi.checked) sel += fi.size
            }
            var shown = 0
            for (grp in g) {
                if (shown >= 300) break
                shown++
                val hr = Row(T_GROUP)
                hr.title = File(grp.files[0].path).name + "  x" + grp.files.size.toString()
                hr.sub = (if (similar) "look-alikes" else "each " + fmt(grp.size)) + "  ·  can free " + fmt(grp.waste) + "  ·  tap = select extras"
                hr.group = grp
                rows.add(hr)
                for (k in grp.files.indices) {
                    val ir = itemRow(grp.files[k])
                    if (k == 0) ir.sub = "★ keep  ·  " + ir.sub
                    rows.add(ir)
                }
            }
            if (g.isEmpty()) {
                val r = Row(T_TEXT)
                r.title = if (similar) "No similar photos found." else "No duplicate files found."
                rows.add(r)
            }
            filesInfo.text = g.size.toString() + " groups  ·  can free " + fmt(wasted) + "  (★ = suggested keeper)"
        }
        filesAdapter.submitRows(rows)
        btnFilesDel.text = "Delete selected  ·  " + fmt(sel)
        btnFilesDel.isEnabled = sel > 0L && !busyDelete && !busy
    }

    private fun hashFile(f: File, limit: Long): String? {
        try {
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(65536)
            val fis = FileInputStream(f)
            try {
                var remaining = if (limit > 0L) limit else Long.MAX_VALUE
                while (remaining > 0L) {
                    val want = if (remaining < buf.size.toLong()) remaining.toInt() else buf.size
                    val n = fis.read(buf, 0, want)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    remaining -= n.toLong()
                }
            } finally {
                fis.close()
            }
            val dg = md.digest()
            val sb = StringBuilder()
            for (b in dg) sb.append(String.format("%02x", b))
            return sb.toString()
        } catch (e: Exception) {
            return null
        }
    }

    private fun findDuplicates() {
        val d = scan ?: return
        if (dupBusy) return
        dupBusy = true
        dupCancel = false
        dupDone = 0
        val cands = ArrayList<Long>()
        for ((sz, lst) in d.sizeMap) if (lst.size >= 2) cands.add(sz)
        cands.sortDescending()
        dupTotal = cands.size
        rebuildFiles()
        val poll = object : Runnable {
            override fun run() {
                if (dupBusy) {
                    if (filesMode == 2) rebuildFiles()
                    ui.postDelayed(this, 700)
                }
            }
        }
        ui.postDelayed(poll, 700)
        Thread(Runnable {
            val groups = ArrayList<DupGroup>()
            for (sz in cands) {
                if (dupCancel) break
                val lst = d.sizeMap[sz]
                dupDone++
                if (lst == null) continue
                val quick = HashMap<String, ArrayList<String>>()
                for (p in lst) {
                    val q = hashFile(File(p), 65536L) ?: continue
                    var l = quick[q]
                    if (l == null) {
                        l = ArrayList<String>()
                        quick[q] = l
                    }
                    l.add(p)
                }
                for (ql in quick.values) {
                    if (ql.size < 2) continue
                    val full = HashMap<String, ArrayList<String>>()
                    for (p in ql) {
                        val hh = hashFile(File(p), 0L) ?: continue
                        var l = full[hh]
                        if (l == null) {
                            l = ArrayList<String>()
                            full[hh] = l
                        }
                        l.add(p)
                    }
                    for (fl in full.values) {
                        if (fl.size < 2) continue
                        fl.sortWith(Comparator { a, b -> File(a).lastModified().compareTo(File(b).lastModified()) })
                        val grp = DupGroup(sz)
                        grp.waste = sz * (fl.size - 1).toLong()
                        for (k in fl.indices) grp.files.add(FItem(fl[k], sz, false, -2, k > 0))
                        groups.add(grp)
                    }
                }
            }
            groups.sortWith(Comparator { a, b -> b.waste.compareTo(a.waste) })
            runOnUiThread {
                dupGroups = groups
                dupBusy = false
                rebuildFiles()
            }
        }).start()
    }

    private fun lum(c: Int): Int {
        return (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
    }

    // 64-bit difference hash of an image (9x8 grayscale). Returns [hash, aspect*1000] or null.
    private fun dHash(path: String): LongArray? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val minSide = if (o.outWidth < o.outHeight) o.outWidth else o.outHeight
            var s = 1
            while (minSide / (s * 2) >= 96) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = BitmapFactory.decodeFile(path, o2) ?: return null
            val sm = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            if (sm !== bmp) bmp.recycle()
            var h = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val a = lum(sm.getPixel(x, y))
                    val b = lum(sm.getPixel(x + 1, y))
                    h = h shl 1
                    if (a > b) h = h or 1L
                }
            }
            sm.recycle()
            val ratio = (o.outWidth.toLong() * 1000L) / o.outHeight.toLong()
            return longArrayOf(h, ratio)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun ufFind(p: IntArray, x0: Int): Int {
        var x = x0
        while (p[x] != x) {
            p[x] = p[p[x]]
            x = p[x]
        }
        return x
    }

    private fun findSimilar() {
        val d = scan ?: return
        if (simBusy) return
        simBusy = true
        dupCancel = false
        simDone = 0
        val paths = ArrayList<String>(d.photos)
        simTotal = paths.size
        rebuildFiles()
        val poll = object : Runnable {
            override fun run() {
                if (simBusy) {
                    if (filesMode == 3) rebuildFiles()
                    ui.postDelayed(this, 700)
                }
            }
        }
        ui.postDelayed(poll, 700)
        Thread(Runnable {
            val n = paths.size
            val hashes = LongArray(n)
            val ratios = LongArray(n)
            val okArr = BooleanArray(n)
            for (i in 0 until n) {
                if (dupCancel) break
                val r = dHash(paths[i])
                if (r != null && r[0] != 0L) {
                    hashes[i] = r[0]
                    ratios[i] = r[1]
                    okArr[i] = true
                }
                simDone = i + 1
            }
            val parent = IntArray(n)
            for (i in 0 until n) parent[i] = i
            val buckets = HashMap<Int, ArrayList<Int>>()
            for (i in 0 until n) {
                if (!okArr[i]) continue
                for (b in 0 until 4) {
                    val key = (b shl 16) or (((hashes[i] ushr (b * 16)) and 0xFFFFL).toInt())
                    var l = buckets[key]
                    if (l == null) {
                        l = ArrayList<Int>()
                        buckets[key] = l
                    }
                    l.add(i)
                }
            }
            for (l in buckets.values) {
                if (l.size < 2 || l.size > 400) continue
                for (a in 0 until l.size) {
                    for (b in (a + 1) until l.size) {
                        val i = l[a]
                        val j = l[b]
                        if (java.lang.Long.bitCount(hashes[i] xor hashes[j]) > 6) continue
                        val ra = ratios[i]
                        val rb = ratios[j]
                        val diff = if (ra > rb) ra - rb else rb - ra
                        val mx = if (ra > rb) ra else rb
                        if (diff * 20L > mx) continue
                        val fi = ufFind(parent, i)
                        val fj = ufFind(parent, j)
                        if (fi != fj) parent[fi] = fj
                    }
                }
            }
            val gm = HashMap<Int, ArrayList<Int>>()
            for (i in 0 until n) {
                if (!okArr[i]) continue
                val r = ufFind(parent, i)
                var l = gm[r]
                if (l == null) {
                    l = ArrayList<Int>()
                    gm[r] = l
                }
                l.add(i)
            }
            val groups = ArrayList<DupGroup>()
            for (l in gm.values) {
                if (l.size < 2) continue
                val items = ArrayList<FItem>()
                var total = 0L
                var maxS = 0L
                for (idx in l) {
                    val len = File(paths[idx]).length()
                    total += len
                    if (len > maxS) maxS = len
                    items.add(FItem(paths[idx], len, false, -2, false))
                }
                items.sortWith(Comparator { a, b -> b.size.compareTo(a.size) })
                val grp = DupGroup(maxS)
                grp.waste = total - maxS
                grp.files.addAll(items)
                groups.add(grp)
            }
            groups.sortWith(Comparator { a, b -> b.waste.compareTo(a.waste) })
            runOnUiThread {
                simGroups = groups
                simBusy = false
                rebuildFiles()
            }
        }).start()
    }

    // ── Browse (disk-usage explorer) ────────────────────────────────────────









    // ── Auto clean ──────────────────────────────────────────────────────────

    private fun autoEnabled(): Boolean {
        return prefs.getBoolean("auto", false)
    }

    private fun setAuto(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        try {
            val js = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (on) {
                val ji = JobInfo.Builder(1001, ComponentName(this, AutoCleanService::class.java))
                    .setPeriodic(7L * 24L * 3600L * 1000L)
                    .setRequiresCharging(true)
                    .build()
                js.schedule(ji)
            } else {
                js.cancel(1001)
            }
        } catch (e: Exception) {
            toast("Could not change the schedule")
        }
    }

    private fun deleteFilesSelected() {
        if (filesMode == 4) {
            deleteTreeSelected()
            return
        }
        val d = scan ?: return
        val items = ArrayList<FItem>()
        if (filesMode == 1) {
            val minB = largeMb.toLong() * 1048576L
            for (fi in d.big) if (fi.checked && fi.size >= minB) items.add(fi)
        } else if (filesMode == 2 || filesMode == 3) {
            val g = (if (filesMode == 3) simGroups else dupGroups) ?: return
            var protectedGroups = 0
            for (grp in g) {
                var c = 0
                for (fi in grp.files) if (fi.checked) c++
                if (c == grp.files.size) {
                    grp.files[0].checked = false
                    protectedGroups++
                }
                for (fi in grp.files) if (fi.checked) items.add(fi)
            }
            if (protectedGroups > 0) toast("Kept one copy in " + protectedGroups.toString() + " group(s)")
        }
        confirmClean(items)
    }

    // ── Auto cache clean (Accessibility helper) ────────────────────────────

    private fun startAutoCache() {
        val svc = CacheCleanService.instance
        if (svc == null) {
            AlertDialog.Builder(this)
                .setTitle("Turn on the cache-cleaning helper")
                .setMessage(
                    "Android does not let apps clear other apps' caches directly. The helper (an Accessibility service) " +
                        "opens each app's Storage page and taps \"Clear cache\" for you.\n\n" +
                        "1. Tap Open settings\n" +
                        "2. Find Deep Cleaner > \"Deep Cleaner cache cleaning\" and switch it ON\n" +
                        "3. Come back here and tap Auto-clean again\n\n" +
                        "If the switch is greyed out (Android 13+): Settings > Apps > Deep Cleaner > menu (3 dots) > Allow restricted settings.\n\n" +
                        "It only acts while you run Auto-clean here. Your apps' data and logins are not touched, only cache."
                )
                .setPositiveButton("Open settings", DialogInterface.OnClickListener { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {
                        toast("Open Settings > Accessibility")
                    }
                })
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (!usageOk) {
            toast("Grant usage access first (needed to know cache sizes)")
            openUsageSettings()
            return
        }
        val list = ArrayList<AppRec>()
        for (a in appsAll) {
            if (a.cache >= 1048576L && a.pkg != packageName) list.add(a)
        }
        list.sortWith(Comparator { a, b -> b.cache.compareTo(a.cache) })
        while (list.size > 60) list.removeAt(list.size - 1)
        if (list.isEmpty()) {
            toast("No app has 1 MB or more of cache")
            return
        }
        var total = 0L
        for (a in list) total += a.cache
        val pkgs = ArrayList<String>()
        for (a in list) pkgs.add(a.pkg)
        AlertDialog.Builder(this)
            .setTitle("Clear cache of " + list.size.toString() + " apps?")
            .setMessage(
                "About " + fmt(total) + " of cache, biggest apps first.\n\n" +
                    "The screen will flip through Settings pages for roughly " + (list.size * 4).toString() +
                    " seconds. Please do not touch the phone until you are brought back here."
            )
            .setPositiveButton("Start", DialogInterface.OnClickListener { _, _ ->
                CacheJob.queue.clear()
                CacheJob.queue.addAll(pkgs)
                svc.beginJob()
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Non-destructive space savers: system cache trim, move to SD, shrink photos ──

    private fun freeBytes(): Long {
        return try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun trimSystemCaches() {
        if (busyDelete) return
        toast("Asking Android to free app caches...")
        Thread(Runnable {
            val before = freeBytes()
            var msg = ""
            try {
                val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val uuid = sm.getUuidForPath(filesDir)
                val allocatable = sm.getAllocatableBytes(uuid)
                val want = allocatable - 200L * 1048576L
                if (want <= before) {
                    msg = "Android reports no extra app cache that it can clear right now."
                } else {
                    val tmp = File(cacheDir, "trim.tmp")
                    val fos = FileOutputStream(tmp)
                    try {
                        sm.allocateBytes(fos.fd, want)
                    } finally {
                        try {
                            fos.close()
                        } catch (e: Exception) {
                        }
                        tmp.delete()
                    }
                    val after = freeBytes()
                    val gain = if (after > before) after - before else 0L
                    msg = "Free space: " + fmt(before) + " -> " + fmt(after) + "  (+" + fmt(gain) + ")"
                    if (gain > 0L) {
                        prefs.edit()
                            .putLong("freed_total", prefs.getLong("freed_total", 0L) + gain)
                            .putLong("last_clean", System.currentTimeMillis())
                            .apply()
                    }
                }
            } catch (e: Exception) {
                msg = "Android did not clear anything (" + (e.message ?: "refused") + ")."
            }
            val shown = msg
            runOnUiThread {
                refreshHome()
                AlertDialog.Builder(this)
                    .setTitle("System cache trim")
                    .setMessage(shown + "\n\nAndroid itself decides which cache files go. App data, logins and your files are not touched.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }).start()
    }

    private fun onFilesMove() {
        val d = scan ?: return
        if (filesMode == 1) {
            val minB = largeMb.toLong() * 1048576L
            val items = ArrayList<FItem>()
            for (fi in d.big) if (fi.checked && fi.size >= minB) items.add(fi)
            askMove(items)
        } else if (filesMode == 5) {
            doShrink()
        }
    }

    private fun onFilesAct2() {
        if (filesMode == 4) {
            treeTop = (treeTop + 1) % 3
            renderTree()
            return
        }
        if (filesMode == 5) {
            val opts = intArrayOf(0, 4096, 3000, 2048, 1600)
            var idx = 0
            for (k in opts.indices) if (opts[k] == shrinkDim) idx = k
            shrinkDim = opts[(idx + 1) % opts.size]
            rebuildFiles()
        }
    }

    private fun askMove(items: ArrayList<FItem>) {
        if (items.isEmpty()) {
            toast("Nothing selected")
            return
        }
        moveItems = items
        AlertDialog.Builder(this)
            .setTitle("Move " + items.size.toString() + " file(s)?")
            .setMessage(
                "Each file is COPIED to the folder you pick (choose a folder on your SD card, USB drive or another storage), " +
                    "checked by size, and only then removed from here. If a copy fails, the original stays.\n\n" +
                    "On Android 11+ you cannot pick the root of a drive: create or choose a folder first."
            )
            .setPositiveButton("Choose folder", DialogInterface.OnClickListener { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 4001)
                } catch (e: Exception) {
                    toast("No folder picker available")
                }
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4001 && resultCode == RESULT_OK && data != null) {
            val tree = data.data
            if (tree != null) doMove(tree)
        }
    }

    private fun doMove(tree: Uri) {
        if (busyDelete) return
        busyDelete = true
        val items = ArrayList<FItem>(moveItems)
        filesInfo.text = "Moving files..."
        Thread(Runnable {
            val cr = contentResolver
            var moved = 0
            var failed = 0
            var freed = 0L
            val gone = ArrayList<String>()
            try {
                val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val dest = DocumentsContract.createDocument(cr, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, "Deep Cleaner moved")
                if (dest == null) throw java.io.IOException("cannot create folder")
                for (fi in items) {
                    val src = File(fi.path)
                    if (!src.isFile || isProtectedPath(fi.path)) {
                        failed++
                        continue
                    }
                    var out: Uri? = null
                    var ok = false
                    try {
                        val ext = src.name.substringAfterLast('.', "").lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                        out = DocumentsContract.createDocument(cr, dest, mime, src.name)
                        if (out != null) {
                            val os = cr.openOutputStream(out)
                            if (os != null) {
                                val ins = FileInputStream(src)
                                try {
                                    val buf = ByteArray(131072)
                                    while (true) {
                                        val n = ins.read(buf)
                                        if (n <= 0) break
                                        os.write(buf, 0, n)
                                    }
                                    os.flush()
                                } finally {
                                    ins.close()
                                    os.close()
                                }
                                var sz = -1L
                                val pfd = cr.openFileDescriptor(out, "r")
                                if (pfd != null) {
                                    sz = pfd.statSize
                                    pfd.close()
                                }
                                if (sz < 0L) {
                                    val c = cr.query(out, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                                    if (c != null) {
                                        if (c.moveToFirst() && !c.isNull(0)) sz = c.getLong(0)
                                        c.close()
                                    }
                                }
                                ok = sz == src.length()
                            }
                        }
                    } catch (e: Exception) {
                        ok = false
                    }
                    if (ok) {
                        if (src.delete()) {
                            moved++
                            freed += fi.size
                            gone.add(fi.path)
                        } else {
                            failed++
                        }
                    } else {
                        failed++
                        if (out != null) {
                            try {
                                DocumentsContract.deleteDocument(cr, out)
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                failed = items.size - moved
            }
            scanMedia(gone)
            val m = moved
            val f2 = failed
            val fr = freed
            runOnUiThread {
                busyDelete = false
                if (fr > 0L) {
                    prefs.edit().putLong("freed_total", prefs.getLong("freed_total", 0L) + fr).apply()
                }
                pruneModel(HashSet<String>(gone))
                rebuildFiles()
                refreshHome()
                AlertDialog.Builder(this)
                    .setTitle("Move finished")
                    .setMessage(
                        "Moved " + m.toString() + " file(s) to the folder \"Deep Cleaner moved\".\n" +
                            "Space freed here: " + fmt(fr) + (if (f2 > 0) "\n" + f2.toString() + " file(s) were left where they are (copy failed or protected)." else "")
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }).start()
    }

    private val exifTags = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER
    )

    // Re-compresses one JPEG in place (same name, date, EXIF). Returns the new size, or -1 if left untouched.
    private fun shrinkOne(path: String, q: Int, dim: Int): Long {
        val src = File(path)
        if (!src.isFile) return -1L
        val tmp = File(src.parentFile, "." + src.name + ".dctmp")
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, o)
            val w = o.outWidth
            val h = o.outHeight
            if (w <= 0 || h <= 0) return -1L
            val longSide = if (w > h) w else h
            var sample = 1
            if (dim > 0 && longSide > dim) {
                while (longSide / (sample * 2) >= dim) sample *= 2
            }
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            var bmp = BitmapFactory.decodeFile(path, o2) ?: return -1L
            if (dim > 0) {
                val ls = if (bmp.width > bmp.height) bmp.width else bmp.height
                if (ls > dim) {
                    val sc = dim.toFloat() / ls.toFloat()
                    val nw = Math.max(1, Math.round(bmp.width * sc))
                    val nh = Math.max(1, Math.round(bmp.height * sc))
                    val nb = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                    if (nb !== bmp) {
                        bmp.recycle()
                        bmp = nb
                    }
                }
            }
            val fos = FileOutputStream(tmp)
            val okc = try {
                bmp.compress(Bitmap.CompressFormat.JPEG, q, fos)
            } finally {
                fos.close()
            }
            bmp.recycle()
            if (!okc) {
                tmp.delete()
                return -1L
            }
            val oldLen = src.length()
            if (tmp.length() <= 0L || tmp.length() > (oldLen * 85L) / 100L) {
                tmp.delete()
                return -1L
            }
            var metaOk = true
            try {
                val a = ExifInterface(path)
                val b = ExifInterface(tmp.path)
                for (tag in exifTags) {
                    val v = a.getAttribute(tag)
                    if (v != null) b.setAttribute(tag, v)
                }
                b.saveAttributes()
            } catch (e: Exception) {
                metaOk = false
            }
            if (!metaOk) {
                var ori = 1
                try {
                    ori = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                } catch (e: Exception) {
                }
                if (ori != 1 && ori != 0) {
                    tmp.delete()
                    return -1L
                }
            }
            if (tmp.length() >= oldLen) {
                tmp.delete()
                return -1L
            }
            tmp.setLastModified(src.lastModified())
            if (!tmp.renameTo(src)) {
                tmp.delete()
                return -1L
            }
            return src.length()
        } catch (e: Throwable) {
            try {
                tmp.delete()
            } catch (e2: Exception) {
            }
            return -1L
        }
    }

    private fun doShrink() {
        val d = scan ?: return
        if (busyDelete) return
        val items = ArrayList<FItem>()
        for (fi in d.jpegs) if (fi.checked) items.add(fi)
        if (items.isEmpty()) {
            toast("Nothing selected")
            return
        }
        val q = shrinkQ
        val dim = shrinkDim
        AlertDialog.Builder(this)
            .setTitle("Shrink " + items.size.toString() + " photos?")
            .setMessage(
                "Quality " + q.toString() + "%" + (if (dim > 0) ", longest side " + dim.toString() + " px" else ", original size") + ".\n\n" +
                    "Each photo stays in the same place with the same name, date and location data; only the picture data is " +
                    "re-compressed. This is lossy and cannot be undone. Photos that would not get at least 15% smaller are left untouched."
            )
            .setPositiveButton("Shrink", DialogInterface.OnClickListener { _, _ ->
                busyDelete = true
                btnFilesMove.isEnabled = false
                val results = HashMap<String, Long>()
                Thread(Runnable {
                    var saved = 0L
                    var done = 0
                    var skipped = 0
                    val changed = ArrayList<String>()
                    for (fi in items) {
                        val ns = shrinkOne(fi.path, q, dim)
                        if (ns > 0L && ns < fi.size) {
                            saved += fi.size - ns
                            results[fi.path] = ns
                            changed.add(fi.path)
                            done++
                        } else {
                            skipped++
                        }
                        val k = done + skipped
                        runOnUiThread { filesInfo.text = "Shrinking photos... " + k.toString() + " / " + items.size.toString() }
                    }
                    scanMedia(changed)
                    val sv = saved
                    val dn = done
                    val sk = skipped
                    runOnUiThread {
                        busyDelete = false
                        for (idx in d.jpegs.indices) {
                            val fi = d.jpegs[idx]
                            val ns = results[fi.path]
                            if (ns != null) d.jpegs[idx] = FItem(fi.path, ns, false, -4, false)
                        }
                        if (sv > 0L) {
                            prefs.edit()
                                .putLong("freed_total", prefs.getLong("freed_total", 0L) + sv)
                                .putLong("last_clean", System.currentTimeMillis())
                                .apply()
                        }
                        rebuildFiles()
                        refreshHome()
                        AlertDialog.Builder(this)
                            .setTitle("Shrink finished")
                            .setMessage("Shrunk " + dn.toString() + " photo(s), saved " + fmt(sv) + ".\n" + sk.toString() + " left untouched (already small, or not worth it).")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }).start()
            })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rebuildShrink(d: ScanData) {
        val rows = ArrayList<Row>()
        var n = 0
        var tot = 0L
        var sel = 0L
        for (fi in d.jpegs) {
            n++
            tot += fi.size
            if (fi.checked) sel += fi.size
            if (rows.size < 500) rows.add(itemRow(fi))
        }
        if (rows.isEmpty()) {
            val r = Row(T_TEXT)
            r.title = "No JPEG photos of 0.5 MB or more found."
            rows.add(r)
        }
        filesAdapter.submitRows(rows)
        filesInfo.text = n.toString() + " JPEG photos, " + fmt(tot) + " in total. Shrinking keeps every photo, name, date and location; " +
            "it only re-compresses the picture (lossy)."
        btnFilesAct.visibility = View.VISIBLE
        btnFilesAct.text = "Quality: " + shrinkQ.toString() + "%  (tap)"
        btnFilesAct2.text = "Max size: " + (if (shrinkDim == 0) "original" else shrinkDim.toString() + " px") + "  (tap)"
        btnFilesMove.text = "Shrink selected photos  ·  " + fmt(sel)
        btnFilesMove.isEnabled = sel > 0L && !busyDelete
    }

    // ── Storage tree explorer (internal storage / SD card, hidden folders included) ──

    private val dateFmt = java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private var treeRoot: TNode? = null
    @Volatile private var treeGen = 0
    private var treeSort = 0
    private var treeTop = 0
    private var volRoots = ArrayList<String>()
    private var volIdx = 0
    private var curRoot: String = Environment.getExternalStorageDirectory().path

    private fun detectVolumes() {
        val out = ArrayList<String>()
        try {
            val dirs = getExternalFilesDirs(null)
            for (d in dirs) {
                if (d == null) continue
                val p = d.path
                val i = p.indexOf("/Android/data")
                if (i > 0) {
                    val r = p.substring(0, i)
                    if (!out.contains(r)) out.add(r)
                }
            }
        } catch (e: Exception) {
        }
        if (out.isEmpty()) out.add(rootPath)
        volRoots = out
        Vols.roots = ArrayList<String>(out)
    }

    private fun volLabel(i: Int): String {
        if (i == 0 || i >= volRoots.size) return "Internal storage"
        return "SD card / USB " + File(volRoots[i]).name
    }

    private fun startTree() {
        treeGen = treeGen + 1
        if (volIdx >= volRoots.size) volIdx = 0
        curRoot = volRoots[volIdx]
        val root = TNode(curRoot, volLabel(volIdx), true, -1L, -1, false, 0L)
        root.expanded = true
        treeRoot = root
        renderTree()
        loadChildren(root)
    }

    private fun isRestrictedDir(path: String): Boolean {
        return path.endsWith("/Android/data") || path.endsWith("/Android/obb")
    }

    private fun sumKnown(list: List<TNode>): Long {
        var t = 0L
        for (n in list) if (n.size > 0L) t += n.size
        return t
    }

    private fun sortNodes(list: ArrayList<TNode>) {
        if (treeSort == 0) {
            list.sortWith(Comparator { a, b -> b.size.compareTo(a.size) })
        } else if (treeSort == 1) {
            list.sortWith(Comparator { a, b -> a.name.compareTo(b.name, true) })
        } else {
            list.sortWith(Comparator { a, b -> b.mtime.compareTo(a.mtime) })
        }
        for (n in list) {
            if (n.children.isNotEmpty()) sortNodes(n.children)
        }
    }

    private fun loadChildren(n: TNode) {
        if (n.loading) return
        n.loading = true
        val gen = treeGen
        Thread(Runnable {
            val kids = File(n.path).listFiles()
            val list = ArrayList<TNode>()
            if (kids != null) {
                for (k in kids) {
                    val isD = k.isDirectory
                    val nm = k.name
                    val sz = if (isD) -1L else k.length()
                    list.add(TNode(k.path, nm, isD, sz, n.level + 1, nm.startsWith(".") || n.hidden, k.lastModified()))
                }
            }
            val restricted = kids == null
            val work = ArrayList<TNode>(list)
            runOnUiThread {
                if (treeGen == gen) {
                    n.children = list
                    n.itemCount = list.size
                    n.restricted = restricted
                    n.loaded = true
                    sortNodes(n.children)
                    renderTree()
                }
            }
            var lastRender = System.currentTimeMillis()
            for (c in work) {
                if (treeGen != gen) return@Runnable
                if (!c.isDir) continue
                if (isRestrictedDir(c.path)) {
                    c.size = -2L
                } else if (isLinkFile(File(c.path))) {
                    c.size = 0L
                } else {
                    c.size = treeSize(File(c.path))
                }
                val now = System.currentTimeMillis()
                if (now - lastRender > 600L) {
                    lastRender = now
                    runOnUiThread {
                        if (treeGen == gen) renderTree()
                    }
                }
            }
            runOnUiThread {
                if (treeGen == gen) {
                    n.loading = false
                    if (n.size < 0L && !n.restricted) n.size = sumKnown(n.children)
                    sortNodes(n.children)
                    renderTree()
                }
            }
        }).start()
    }

    private fun sizeText(n: TNode): String {
        if (n.size >= 0L) return fmt(n.size)
        if (n.size == -2L) return "n/a"
        return "..."
    }

    private fun flattenTree(list: List<TNode>, parentSize: Long, out: ArrayList<Row>, top: Boolean) {
        var shown = 0
        for (n in list) {
            if (top) {
                if (treeTop == 1 && !n.hidden) continue
                if (treeTop == 2 && n.hidden) continue
            }
            if (shown >= 1500) {
                val mr = Row(T_TEXT)
                mr.title = "... more items not shown (open sub-folders to narrow down)"
                mr.indentDp = 6 + 14 * n.level
                out.add(mr)
                break
            }
            shown++
            val r = Row(if (n.isDir) T_HEAD else T_ITEM)
            r.node = n
            r.title = n.name + (if (n.isDir) "/" else "")
            r.right = sizeText(n)
            r.rightColor = if (n.size > 1073741824L) Col.BAD else if (n.size > 209715200L) Col.WARN else Col.TXT
            r.showCb = true
            r.checked = n.checked
            r.expanded = n.expanded
            r.indentDp = 6 + 14 * n.level + (if (n.isDir) 0 else 22)
            var sub = ""
            if (n.isDir) {
                sub = if (n.restricted) "restricted by Android" else if (n.loaded) n.itemCount.toString() + " items" else "tap to open"
            } else {
                sub = dateFmt.format(java.util.Date(n.mtime))
            }
            if (n.hidden) sub = "hidden  ·  " + sub
            if (parentSize > 0L && n.size >= 0L) sub += "  ·  " + (n.size * 100L / parentSize).toString() + "%"
            r.sub = sub
            out.add(r)
            if (n.isDir && n.expanded) {
                if (!n.loaded) {
                    val tr = Row(T_TEXT)
                    tr.title = "reading..."
                    tr.indentDp = 6 + 14 * (n.level + 1)
                    out.add(tr)
                } else if (n.restricted) {
                    val tr = Row(T_TEXT)
                    tr.title = "Android blocks this folder for all apps. Use the Apps tab for per-app cache and data sizes."
                    tr.tag = "apps"
                    tr.indentDp = 6 + 14 * (n.level + 1)
                    out.add(tr)
                } else if (n.children.isEmpty()) {
                    val tr = Row(T_TEXT)
                    tr.title = "empty folder"
                    tr.indentDp = 6 + 14 * (n.level + 1)
                    out.add(tr)
                } else {
                    flattenTree(n.children, if (n.size > 0L) n.size else sumKnown(n.children), out, false)
                }
            }
        }
    }

    private fun selTreeSize(list: List<TNode>): Long {
        var t = 0L
        for (n in list) {
            if (n.checked) {
                if (n.size > 0L) t += n.size
            } else if (n.children.isNotEmpty()) {
                t += selTreeSize(n.children)
            }
        }
        return t
    }

    private fun renderTree() {
        if (filesMode != 4) return
        val root = treeRoot ?: return
        val rows = ArrayList<Row>()
        if (volRoots.size > 1) {
            val vr = Row(T_GROUP)
            vr.title = "💾  Drive: " + volLabel(volIdx) + "  (tap to switch)"
            vr.tag = "vol"
            rows.add(vr)
        }
        val rr = Row(T_GROUP)
        rr.title = "↻  Rescan sizes"
        rr.tag = "refresh"
        rows.add(rr)
        if (!root.loaded) {
            val tr = Row(T_TEXT)
            tr.title = "Reading folders..."
            rows.add(tr)
        } else {
            val ps = if (root.size > 0L) root.size else sumKnown(root.children)
            flattenTree(root.children, ps, rows, true)
        }
        filesAdapter.submitRows(rows)

        var total = 0L
        var free = 0L
        try {
            val st = StatFs(curRoot)
            total = st.totalBytes
            free = st.availableBytes
        } catch (e: Exception) {
        }
        val used = total - free
        var listed = 0L
        var hiddenSum = 0L
        for (c in root.children) {
            if (c.size > 0L) {
                listed += c.size
                if (c.hidden) hiddenSum += c.size
            }
        }
        val other = if (used > listed) used - listed else 0L
        val sel = selTreeSize(root.children)
        filesInfo.text = volLabel(volIdx) + ": used " + fmt(used) + " of " + fmt(total) + "  ·  free " + fmt(free) +
            "\nListed folders " + fmt(listed) + "  ·  hidden " + fmt(hiddenSum) + "  ·  apps/system/restricted " + fmt(other) +
            "\nTap a folder to open it, tick items to delete. Long-press for details."
        btnFilesAct.text = "Sort: " + (if (treeSort == 0) "size" else if (treeSort == 1) "name" else "date")
        btnFilesAct2.text = "Top level: " + (if (treeTop == 0) "all" else if (treeTop == 1) "hidden only" else "visible only")
        btnFilesDel.text = "Delete selected  ·  " + fmt(sel)
        btnFilesDel.isEnabled = sel > 0L && !busyDelete
    }

    private fun treeRowClick(r: Row) {
        if (r.tag == "vol") {
            volIdx = (volIdx + 1) % volRoots.size
            startTree()
            return
        }
        if (r.tag == "refresh") {
            startTree()
            return
        }
        if (r.tag == "apps") {
            showTab(2)
            return
        }
        val n = r.node ?: return
        if (n.isDir) {
            n.expanded = !n.expanded
            if (n.expanded && !n.loaded && !n.loading) {
                loadChildren(n)
            }
            renderTree()
        } else {
            showNodeDetails(n)
        }
    }

    private fun treeRowCheck(r: Row, checked: Boolean) {
        val n = r.node ?: return
        n.checked = checked
        renderTree()
    }

    private fun treeRowLong(r: Row) {
        val n = r.node ?: return
        AlertDialog.Builder(this)
            .setTitle(n.name)
            .setItems(arrayOf("Details", "Delete..."), DialogInterface.OnClickListener { _, which ->
                if (which == 0) showNodeDetails(n) else deleteNode(n)
            })
            .show()
    }

    private fun showNodeDetails(n: TNode) {
        val sb = StringBuilder()
        sb.append(n.path).append("\n\n")
        sb.append("Size: ").append(sizeText(n)).append("\n")
        sb.append("Modified: ").append(dateFmt.format(java.util.Date(n.mtime))).append("\n")
        if (n.isDir && n.loaded) sb.append("Items inside: ").append(n.itemCount.toString()).append("\n")
        if (n.hidden) sb.append("Hidden item\n")
        AlertDialog.Builder(this)
            .setTitle(n.name)
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun deleteNode(n: TNode) {
        if (isProtectedPath(n.path)) {
            toast("Protected top-level folder: open it and delete items inside instead")
            return
        }
        val l = ArrayList<FItem>()
        l.add(FItem(n.path, if (n.size > 0L) n.size else 0L, n.isDir, -3, true))
        confirmClean(l)
    }

    private fun collectChecked(list: List<TNode>, out: ArrayList<FItem>): Int {
        var prot = 0
        for (n in list) {
            if (n.checked) {
                if (isProtectedPath(n.path)) {
                    prot++
                } else {
                    out.add(FItem(n.path, if (n.size > 0L) n.size else 0L, n.isDir, -3, true))
                }
            } else if (n.children.isNotEmpty()) {
                prot += collectChecked(n.children, out)
            }
        }
        return prot
    }

    private fun deleteTreeSelected() {
        val root = treeRoot ?: return
        val items = ArrayList<FItem>()
        val prot = collectChecked(root.children, items)
        if (prot > 0) toast(prot.toString() + " protected top-level folder(s) skipped")
        confirmClean(items)
    }

    private fun pruneNodes(list: ArrayList<TNode>, gone: Set<String>): Long {
        var removed = 0L
        val it = list.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (gone.contains(n.path)) {
                if (n.size > 0L) removed += n.size
                it.remove()
            } else if (n.children.isNotEmpty()) {
                val r = pruneNodes(n.children, gone)
                if (n.size >= 0L) n.size = if (n.size > r) n.size - r else 0L
                n.itemCount = n.children.size
                removed += r
            }
        }
        return removed
    }

    private fun pruneTreeRoot(gone: Set<String>) {
        val root = treeRoot ?: return
        val r = pruneNodes(root.children, gone)
        if (root.size >= 0L) root.size = if (root.size > r) root.size - r else 0L
        root.itemCount = root.children.size
    }

    // ── BOOST TAB ───────────────────────────────────────────────────────────

    private fun buildBoost(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(12), dp(12), dp(8))

        col.addView(mkText("RAM boost", 20f, Col.TXT, true))

        ringRam2 = RingView(this)
        col.addView(ringRam2, lpm(MP, dp(140), 0f, 0, 6, 0, 0))
        boostInfo = mkText("", 13f, Col.SUB, false)
        boostInfo.gravity = Gravity.CENTER
        col.addView(boostInfo, lpm(MP, WC, 0f, 0, 2, 0, 6))

        val br = LinearLayout(this)
        br.orientation = LinearLayout.HORIZONTAL
        br.addView(mkBtn("Select all", Col.CARD2, Col.TXT) { boostSelect(true) }, lpm(0, WC, 1f, 0, 0, 4, 0))
        br.addView(mkBtn("Select none", Col.CARD2, Col.TXT) { boostSelect(false) }, lpm(0, WC, 1f, 4, 0, 0, 0))
        col.addView(br, lpm(MP, WC, 0f, 0, 0, 0, 6))

        boostList = ListView(this)
        styleList(boostList)
        boostAdapter = RowAdapter(this, object : RowL() {
            override fun onRow(r: Row) {
                r.checked = !r.checked
                boostAdapter.submitRows(boostRows)
            }

            override fun onCheck(r: Row, checked: Boolean) {
                r.checked = checked
            }
        })
        boostAdapter.attach(boostList)
        col.addView(boostList, LinearLayout.LayoutParams(MP, 0, 1f))

        btnBoost = mkBtn("🚀  Boost now", Col.ACC, Col.DARKTXT) { doBoost() }
        col.addView(btnBoost, lpm(MP, WC, 0f, 0, 8, 0, 0))
        return col
    }

    private fun boostSelect(on: Boolean) {
        for (r in boostRows) r.checked = on
        boostAdapter.submitRows(boostRows)
    }

    private fun loadBoost() {
        if (boostBusy) return
        boostBusy = true
        boostInfo.text = "Loading apps..."
        Thread(Runnable {
            val pm = packageManager
            val usage = hasUsageAccess()
            val lastMap = HashMap<String, Long>()
            try {
                if (usage) {
                    val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val now = System.currentTimeMillis()
                    val list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 3L * 86400000L, now)
                    if (list != null) {
                        for (u in list) {
                            val old = lastMap[u.packageName]
                            if (old == null || u.lastTimeUsed > old) lastMap[u.packageName] = u.lastTimeUsed
                        }
                    }
                }
            } catch (e: Exception) {
            }
            var launcher = ""
            try {
                val hi = Intent(Intent.ACTION_MAIN)
                hi.addCategory(Intent.CATEGORY_HOME)
                val ri = pm.resolveActivity(hi, PackageManager.MATCH_DEFAULT_ONLY)
                if (ri != null) launcher = ri.activityInfo.packageName
            } catch (e: Exception) {
            }
            var ime = ""
            try {
                val s = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                if (s != null) ime = s.substringBefore('/')
            } catch (e: Exception) {
            }
            val recs = ArrayList<AppRec>()
            val apps: List<ApplicationInfo> = try {
                pm.getInstalledApplications(0)
            } catch (e: Exception) {
                ArrayList<ApplicationInfo>()
            }
            for (ai in apps) {
                val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                if (sys) continue
                if (ai.packageName == packageName || ai.packageName == launcher || ai.packageName == ime) continue
                val rec = AppRec(ai.packageName, pm.getApplicationLabel(ai).toString(), false)
                rec.lastUsed = lastMap[ai.packageName] ?: 0L
                recs.add(rec)
            }
            recs.sortWith(Comparator { a, b ->
                if (a.lastUsed != b.lastUsed) b.lastUsed.compareTo(a.lastUsed) else a.label.compareTo(b.label, true)
            })
            runOnUiThread {
                boostRows.clear()
                for (a in recs) {
                    val r = Row(T_APP)
                    r.pkg = a.pkg
                    r.app = a
                    r.title = a.label
                    r.sub = if (usage) {
                        if (a.lastUsed > 0L) "Recently " + ago(a.lastUsed) else "Not used in last 3 days"
                    } else {
                        a.pkg
                    }
                    r.showCb = true
                    r.checked = true
                    boostRows.add(r)
                }
                boostAdapter.submitRows(boostRows)
                boostBusy = false
                boostLoaded = true
                refreshRam()
            }
        }).start()
    }

    private fun doBoost() {
        var n = 0
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = readMem()[1]
        for (r in boostRows) {
            val p = r.pkg
            if (r.checked && p != null) {
                try {
                    am.killBackgroundProcesses(p)
                    n++
                } catch (e: Exception) {
                }
            }
        }
        if (n == 0) {
            toast("Select at least one app")
            return
        }
        btnBoost.isEnabled = false
        boostInfo.text = "Stopping " + n.toString() + " background app(s)..."
        ui.postDelayed({
            val after = readMem()[1]
            val gain = if (after > before) after - before else 0L
            btnBoost.isEnabled = true
            refreshRam()
            AlertDialog.Builder(this)
                .setTitle("Boost finished")
                .setMessage(
                    "Stopped background processes of " + n.toString() + " app(s).\n" +
                        "Free RAM: " + fmt(before) + " -> " + fmt(after) + "  (+" + fmt(gain) + ")\n\n" +
                        "Android restarts some apps by itself, so the gain is temporary."
                )
                .setPositiveButton("OK", null)
                .show()
        }, 1800L)
    }

    // ── SETTINGS / INFO ─────────────────────────────────────────────────────

    private fun showSettings() {
        val items = arrayOf(
            "Large-file minimum: " + largeMb.toString() + " MB",
            "Excluded folders (" + excludedList().size.toString() + ")",
            "Auto-clean weekly (safe categories): " + (if (autoEnabled()) "ON" else "OFF"),
            "Clean new junk when storage grows (on open): " + (if (growthOn()) "ON" else "OFF"),
            "About & limits",
            "Reset 'freed so far' counter"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items, DialogInterface.OnClickListener { _, which ->
                if (which == 0) {
                    chooseLarge()
                } else if (which == 1) {
                    showExcluded()
                } else if (which == 2) {
                    setAuto(!autoEnabled())
                    refreshHome()
                    toast(if (autoEnabled()) "Auto-clean ON: runs about weekly while charging" else "Auto-clean OFF")
                } else if (which == 3) {
                    prefs.edit().putBoolean("growth_on", !growthOn()).apply()
                    saveBaseline()
                    toast(if (growthOn()) "Growth clean ON: checks every time you open the app" else "Growth clean OFF")
                } else if (which == 4) {
                    showAbout()
                } else {
                    prefs.edit().remove("freed_total").remove("last_clean").apply()
                    refreshHome()
                }
            })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseLarge() {
        val opts = intArrayOf(10, 20, 50, 100, 250, 500, 1000)
        val labels = arrayOf("10 MB", "20 MB", "50 MB", "100 MB", "250 MB", "500 MB", "1000 MB")
        var cur = 2
        for (k in opts.indices) if (opts[k] == largeMb) cur = k
        AlertDialog.Builder(this)
            .setTitle("Large-file minimum")
            .setSingleChoiceItems(labels, cur, DialogInterface.OnClickListener { dlg, which ->
                largeMb = opts[which]
                prefs.edit().putInt("large_mb", largeMb).apply()
                rebuildFiles()
                dlg.dismiss()
            })
            .show()
    }

    private fun showExcluded() {
        val list = excludedList()
        val msg = if (list.isEmpty()) {
            "No folders excluded.\nExcluded folders are never scanned or touched.\n\nType a path relative to internal storage, e.g.  DCIM/Camera"
        } else {
            "Never scanned:\n" + list.joinToString("\n") + "\n\nAdd another path below (relative to internal storage)."
        }
        val et = EditText(this)
        et.hint = "e.g. WhatsApp/Media"
        et.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(this)
            .setTitle("Excluded folders")
            .setMessage(msg)
            .setView(et)
            .setPositiveButton("Add", DialogInterface.OnClickListener { _, _ ->
                val t = et.text.toString().trim().trim('/')
                if (t.isNotEmpty()) {
                    val l = excludedList()
                    if (!l.contains(t)) l.add(t)
                    saveExcluded(l)
                    toast("Added")
                }
            })
            .setNeutralButton("Clear all", DialogInterface.OnClickListener { _, _ ->
                saveExcluded(ArrayList<String>())
                toast("Cleared")
            })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("About & limits")
            .setMessage(
                "Safe junk (pre-selected): temp / log files, thumbnails, hidden cache folders, ad-SDK leftovers, viewed status cache. " +
                    "Everything else (trash, empty folders, installers, downloads, screenshots, messenger media, duplicates...) is marked (review) and NOT selected by default. " +
                    "Auto-clean only touches the safe list, and the top-level folders (DCIM, Pictures, Download, Documents...) can never be deleted.\n\n" +
                    "Explore tab (or tap the storage ring on Home): full size tree of internal storage and SD card, hidden folders included. Tap a folder to open it, tick items and delete them.\n\n" +
                    "More space without deleting your files:\n" +
                    "- Shrink: re-compresses big JPEG photos in place (same name, date, location).\n" +
                    "- Move: copies big files to your SD card / USB, checks them, then removes them from the phone.\n" +
                    "- Trim app caches: asks Android itself to clear other apps' caches.\n" +
                    "- Auto-clean ALL app caches (Apps tab) with the Accessibility helper.\n\n" +
                    "Limits of Android: other apps' private data and the folders Android/data and Android/obb cannot be touched without root. " +
                    "RAM boost only stops background processes; Android may restart them.\n\n" +
                    "Deleted files are removed permanently (there is no recycle bin), so check the (review) lists before cleaning."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
