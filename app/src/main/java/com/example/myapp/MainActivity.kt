package com.example.myapp

import android.Manifest
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
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
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
        cats.add(JCat(C_TRASH, "Trash & recycle bins", "Recently deleted items kept by gallery / file apps", false))
        cats.add(JCat(C_CACHEDIR, "Hidden cache folders", "Folders named .cache, .tmp, .temp", false))
        cats.add(JCat(C_ADSDK, "Ad & analytics leftovers", "Known hidden folders left by ad / tracking SDKs", false))
        cats.add(JCat(C_EMPTYDIR, "Empty folders", "Folders with nothing inside", false))
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

// Deletes the given items; paths that are gone afterwards are added to `deleted`. Returns bytes freed.
fun deleteFItems(items: List<FItem>, deleted: ArrayList<String>): Long {
    var freed = 0L
    for (fi in items) {
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

    fun setRows(r: List<Row>) {
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
        h.root.setPadding(dp(if (r.type == T_ITEM) 24 else 10), dp(8), dp(10), dp(8))

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
    @Volatile private var simDone = 0
    @Volatile private var simTotal = 0
    @Volatile private var browseGen = 0
    private var browsePath: String = Environment.getExternalStorageDirectory().path
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
        refreshHome()
        if (curTab == 2) loadApps()
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

        col.addView(
            mkBtn("⚡  Analyze & Quick Clean", Col.ACC, Col.DARKTXT) { startScan(true) },
            lpm(MP, WC, 0f, 0, 18, 0, 0)
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
            homeStorageTv.text = "Free " + fmt(free) + " of " + fmt(total)
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
        junkAdapter.setRows(rows)
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
        for (i in items) total += i.size
        AlertDialog.Builder(this)
            .setTitle("Delete permanently?")
            .setMessage(items.size.toString() + " item(s), " + fmt(total) + ".\nThis cannot be undone.")
            .setPositiveButton("Clean", DialogInterface.OnClickListener { _, _ -> doDelete(items) })
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
        }
        pruneGroups(dupGroups, gone)
        pruneGroups(simGroups, gone)
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
        appsAdapter.setRows(rows)
        appsInfo.text = list.size.toString() + " apps" + (if (usageOk) "  ·  total cache " + fmt(cacheSum) else "") +
            "\nAndroid only lets you clear another app's cache from its own info page."
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

        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        val labels = arrayOf("Storage", "Large", "Dupes", "Similar", "Browse")
        for (i in labels.indices) {
            val b = mkBtn(labels[i], Col.CARD2, Col.TXT) { setFilesMode(i) }
            b.textSize = 11f
            b.setPadding(dp(2), dp(8), dp(2), dp(8))
            tr.addView(b, lpm(0, WC, 1f, 1, 0, 1, 0))
            filesTabBtns.add(b)
        }
        col.addView(tr, lpm(MP, WC, 0f, 0, 8, 0, 6))

        filesInfo = mkText("", 12f, Col.SUB, false)
        col.addView(filesInfo, lpm(MP, WC, 0f, 0, 0, 0, 6))

        btnFilesAct = mkBtn("", Col.CARD2, Col.TXT) { onFilesAct() }
        col.addView(btnFilesAct, lpm(MP, WC, 0f, 0, 0, 0, 6))

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
                    if (r.tag == "up") {
                        browseUp()
                    } else if (fi != null) {
                        if (fi.isDir) browseInto(fi.path) else showPath(fi)
                    }
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
                val fi = r.item
                if (fi == null) return
                if (filesMode == 4) browseDelete(fi) else showPath(fi)
            }

            override fun onCheck(r: Row, checked: Boolean) {
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

        btnFilesDel = mkBtn("Delete selected", Col.BAD, Color.WHITE) { deleteFilesSelected() }
        col.addView(btnFilesDel, lpm(MP, WC, 0f, 0, 8, 0, 0))
        return col
    }

    private fun setFilesMode(m: Int) {
        filesMode = m
        rebuildFiles()
    }

    private fun onFilesAct() {
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
        btnFilesDel.visibility = if (filesMode == 0 || filesMode == 4) View.GONE else View.VISIBLE
        if (filesMode == 4) {
            btnFilesAct.visibility = View.GONE
            loadBrowse()
            return
        }
        if (d == null) {
            filesInfo.text = "No scan data yet."
            btnFilesAct.visibility = View.VISIBLE
            btnFilesAct.text = "Scan storage now"
            storageBox.removeAllViews()
            filesAdapter.setRows(ArrayList<Row>())
            btnFilesDel.isEnabled = false
            return
        }
        if (filesMode == 0) {
            btnFilesAct.visibility = View.GONE
            buildStorageBox(d)
        } else if (filesMode == 1) {
            rebuildLarge(d)
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
        filesAdapter.setRows(rows)
        filesInfo.text = n.toString() + " files >= " + largeMb.toString() + " MB  ·  " + fmt(tot) + "  (tap to select, long-press for path)"
        btnFilesAct.visibility = View.VISIBLE
        btnFilesAct.text = "Minimum size: " + largeMb.toString() + " MB  (tap to change)"
        btnFilesDel.text = "Delete selected  ·  " + fmt(sel)
        btnFilesDel.isEnabled = sel > 0L && !busyDelete
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
        filesAdapter.setRows(rows)
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

    private fun browseUp() {
        if (browsePath == rootPath) return
        val par = File(browsePath).parent
        browsePath = if (par == null || par.length < rootPath.length) rootPath else par
        rebuildFiles()
    }

    private fun browseInto(p: String) {
        browsePath = p
        rebuildFiles()
    }

    private fun browseDelete(fi: FItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete " + File(fi.path).name + "?")
            .setMessage(fmt(fi.size) + (if (fi.isDir) " - folder with everything inside" else "") + "\nThis cannot be undone.")
            .setPositiveButton("Delete", DialogInterface.OnClickListener { _, _ -> doDelete(listOf(fi)) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadBrowse() {
        if (!hasStorageAccess()) {
            filesInfo.text = "Grant all-files access on the Home tab first."
            filesAdapter.setRows(ArrayList<Row>())
            return
        }
        browseGen = browseGen + 1
        val gen = browseGen
        val path = browsePath
        filesInfo.text = rel(path) + "  ·  calculating sizes..."
        val dir = File(path)
        Thread(Runnable {
            val kids = dir.listFiles()
            val list = ArrayList<FItem>()
            var total = 0L
            if (kids != null) {
                for (k in kids) {
                    if (browseGen != gen) return@Runnable
                    val isD = k.isDirectory
                    val sz = if (isD) treeSize(k) else k.length()
                    total += sz
                    list.add(FItem(k.path, sz, isD, -3, false))
                }
            }
            list.sortByDescending { it.size }
            val tot = total
            runOnUiThread {
                if (browseGen == gen) {
                    val rows = ArrayList<Row>()
                    if (path != rootPath) {
                        val up = Row(T_GROUP)
                        up.title = "⬆  Up"
                        up.sub = rel(File(path).parent ?: path)
                        up.tag = "up"
                        rows.add(up)
                    }
                    for (fi in list) {
                        val r = Row(T_ITEM)
                        r.title = File(fi.path).name + (if (fi.isDir) "/" else "")
                        r.sub = if (fi.isDir) "folder" else "file"
                        r.right = fmt(fi.size)
                        r.item = fi
                        rows.add(r)
                    }
                    if (list.isEmpty()) {
                        val r = Row(T_TEXT)
                        r.title = "Empty folder."
                        rows.add(r)
                    }
                    filesAdapter.setRows(rows)
                    filesInfo.text = rel(path) + "  ·  " + fmt(tot) + "  (tap folder to open, long-press to delete)"
                }
            }
        }).start()
    }

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
                boostAdapter.setRows(boostRows)
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
        boostAdapter.setRows(boostRows)
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
                boostAdapter.setRows(boostRows)
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
                "Cleans: temp / log / thumbnail files, gallery trash, hidden cache folders, ad-SDK leftovers, empty folders, " +
                    "viewed WhatsApp status cache, installers, incomplete downloads, old downloads, screenshots, messenger media, " +
                    "old chat backups, leftovers of uninstalled apps, large files, exact duplicates and similar photos. " +
                    "Browse shows what uses space, folder by folder.\n\n" +
                    "Items marked (review) are NOT selected by default - check them first. Auto-clean only touches the safe categories.\n\n" +
                    "Limits of Android: other apps' private caches and the folders Android/data and Android/obb cannot be read or " +
                    "deleted by any normal app without root. Use the Apps tab to jump to each app's Storage page and tap Clear cache.\n\n" +
                    "RAM boost only stops background processes; Android may restart them.\n\n" +
                    "Deleted files are removed permanently (there is no recycle bin)."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
