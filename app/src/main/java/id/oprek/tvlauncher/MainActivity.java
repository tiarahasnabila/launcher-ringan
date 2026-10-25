package id.oprek.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Launcher TV super ringan: satu layar, grid aplikasi, tanpa iklan,
 * tanpa rekomendasi konten, tanpa koneksi internet.
 *
 * Kontrol dengan remote:
 *  - OK / Enter        : buka aplikasi
 *  - Tahan OK          : menu (info aplikasi, sembunyikan)
 */
public class MainActivity extends Activity {

    private static final String PREFS = "launcher_prefs";
    private static final String KEY_HIDDEN = "hidden_packages";

    private static final int TYPE_APP = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_TOGGLE_HIDDEN = 2;

    private static class Item {
        int type;
        String label;
        String pkg;
        ComponentName component;
        Drawable icon;
        boolean hidden;
    }

    private static class Holder {
        ImageView icon;
        TextView label;
    }

    private final List<Item> items = new ArrayList<Item>();
    private ItemAdapter adapter;
    private GridView grid;
    private SharedPreferences prefs;
    private boolean showHidden = false;
    private int loadToken = 0;
    private float density = 1f;

    // Jadwal sholat
    private static final String[] PRAYER_NAMES = {"Subuh", "Zuhur", "Asar", "Magrib", "Isya"};
    private static final TimeZone WIB = TimeZone.getTimeZone("Asia/Jakarta");
    private static final int COLOR_ACCENT = 0xFF4FC3F7;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable prayerTicker = new Runnable() {
        @Override
        public void run() {
            refreshPrayer();
            handler.postDelayed(this, 15000);
        }
    };
    private LinearLayout[] prayerCells;
    private TextView[] prayerTimeViews;
    private TextView nextText;
    private Drawable prayerHighlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        grid.requestFocus();
        handler.removeCallbacks(prayerTicker);
        handler.post(prayerTicker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(prayerTicker);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tombol Home ditekan saat launcher sudah terbuka: kembali ke atas.
        if (grid != null && items.size() > 0) {
            grid.setSelection(0);
        }
    }

    @Override
    public void onBackPressed() {
        // Launcher tidak boleh "keluar" saat tombol Back ditekan.
    }

    // ------------------------------------------------------------------
    // Tampilan (dibuat lewat kode supaya tidak butuh file layout XML)
    // ------------------------------------------------------------------

    private int dp(int value) {
        return Math.round(value * density);
    }

    private void buildUi() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B2A41, 0xFF0B0F17});
        getWindow().setBackgroundDrawable(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(12));

        // Baris atas: jam dan tanggal
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(34);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.DEFAULT_BOLD);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM yyyy");
        date.setFormat12Hour("EEEE, d MMMM yyyy");
        date.setTextSize(18);
        date.setTextColor(0xFFB8C4D6);
        date.setPadding(dp(20), 0, 0, 0);

        top.addView(clock);
        top.addView(date);

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // Waktu sholat berikutnya + hitung mundur (kanan atas)
        nextText = new TextView(this);
        nextText.setTextSize(16);
        nextText.setTextColor(Color.WHITE);
        nextText.setGravity(Gravity.END);
        top.addView(nextText);

        // Baris jadwal sholat (5 waktu)
        prayerHighlight = buildPrayerHighlight();
        LinearLayout prayerRow = new LinearLayout(this);
        prayerRow.setOrientation(LinearLayout.HORIZONTAL);
        prayerRow.setGravity(Gravity.CENTER_VERTICAL);
        prayerRow.setPadding(0, dp(14), 0, 0);

        TextView source = new TextView(this);
        source.setText("Yogyakarta\nMuhammadiyah");
        source.setTextSize(12);
        source.setTextColor(0xFF7F8FA6);
        source.setPadding(0, 0, dp(12), 0);
        prayerRow.addView(source);

        prayerCells = new LinearLayout[5];
        prayerTimeViews = new TextView[5];
        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));

            TextView name = new TextView(this);
            name.setText(PRAYER_NAMES[i]);
            name.setTextSize(13);
            name.setTextColor(0xFFB8C4D6);
            name.setGravity(Gravity.CENTER);

            TextView time = new TextView(this);
            time.setTextSize(22);
            time.setTypeface(Typeface.DEFAULT_BOLD);
            time.setTextColor(Color.WHITE);
            time.setGravity(Gravity.CENTER);

            cell.addView(name);
            cell.addView(time);

            LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cellParams.leftMargin = dp(6);
            cellParams.rightMargin = dp(6);
            prayerRow.addView(cell, cellParams);

            prayerCells[i] = cell;
            prayerTimeViews[i] = time;
        }

        // Grid aplikasi
        grid = new GridView(this);
        grid.setColumnWidth(dp(150));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setClipToPadding(false);
        grid.setPadding(dp(4), dp(20), dp(4), dp(20));
        grid.setVerticalScrollBarEnabled(false);
        grid.setFocusable(true);
        grid.setSelector(buildSelector());
        grid.setDrawSelectorOnTop(false);

        adapter = new ItemAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                if (position >= 0 && position < items.size()) {
                    onItemActivated(items.get(position));
                }
            }
        });

        grid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view,
                                           int position, long id) {
                if (position >= 0 && position < items.size()) {
                    Item item = items.get(position);
                    if (item.type == TYPE_APP) {
                        showOptions(item);
                        return true;
                    }
                }
                return false;
            }
        });

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(prayerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Drawable buildPrayerHighlight() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x224FC3F7);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(2), COLOR_ACCENT);
        return d;
    }

    // ------------------------------------------------------------------
    // Jadwal sholat (dihitung offline oleh PrayerTimes, WIB)
    // ------------------------------------------------------------------

    private static String formatTime(int minutesOfDay) {
        return String.format(Locale.US, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);
    }

    private void refreshPrayer() {
        Calendar now = Calendar.getInstance(WIB);
        int nowSeconds = now.get(Calendar.HOUR_OF_DAY) * 3600
                + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND);

        int[] times = PrayerTimes.compute(now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));

        int next = -1;
        for (int i = 0; i < 5; i++) {
            if (times[i] * 60 > nowSeconds) {
                next = i;
                break;
            }
        }

        int secondsLeft;
        boolean tomorrow = false;
        if (next == -1) {
            // Sudah lewat Isya: tampilkan jadwal besok, berikutnya Subuh
            Calendar t = Calendar.getInstance(WIB);
            t.add(Calendar.DAY_OF_MONTH, 1);
            times = PrayerTimes.compute(t.get(Calendar.YEAR),
                    t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
            next = 0;
            tomorrow = true;
            secondsLeft = (86400 - nowSeconds) + times[0] * 60;
        } else {
            secondsLeft = times[next] * 60 - nowSeconds;
        }

        for (int i = 0; i < 5; i++) {
            prayerTimeViews[i].setText(formatTime(times[i]));
            boolean isNext = (i == next);
            prayerTimeViews[i].setTextColor(isNext ? COLOR_ACCENT : Color.WHITE);
            prayerCells[i].setBackground(isNext ? prayerHighlight : null);
        }

        int minutes = (secondsLeft + 59) / 60;
        int h = minutes / 60;
        int m = minutes % 60;
        String countdown = h > 0 ? (h + " jam " + m + " menit lagi") : (m + " menit lagi");
        nextText.setText(PRAYER_NAMES[next] + " " + formatTime(times[next])
                + (tomorrow ? " (besok)" : "") + "\n" + countdown);
    }

    private Drawable buildSelector() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33FFFFFF);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(3), Color.WHITE);
        return d;
    }

    // ------------------------------------------------------------------
    // Memuat daftar aplikasi
    // ------------------------------------------------------------------

    private void loadApps() {
        final int token = ++loadToken;
        final PackageManager pm = getPackageManager();
        final Set<String> hidden = new HashSet<String>(
                prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        final String ownPackage = getPackageName();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Item> result = queryApps(pm, ownPackage);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || token != loadToken) {
                            return;
                        }
                        applyItems(result, hidden);
                    }
                });
            }
        }).start();
    }

    private List<Item> queryApps(PackageManager pm, String ownPackage) {
        Map<String, Item> map = new LinkedHashMap<String, Item>();
        // Aplikasi TV dulu, lalu aplikasi biasa (termasuk aplikasi sideload non-TV)
        collect(pm, map, Intent.CATEGORY_LEANBACK_LAUNCHER, ownPackage);
        collect(pm, map, Intent.CATEGORY_LAUNCHER, ownPackage);

        List<Item> list = new ArrayList<Item>(map.values());
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(list, new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return collator.compare(a.label, b.label);
            }
        });
        return list;
    }

    private void collect(PackageManager pm, Map<String, Item> map, String category,
                         String ownPackage) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ownPackage) || map.containsKey(pkg)) {
                continue;
            }
            Item item = new Item();
            item.type = TYPE_APP;
            item.pkg = pkg;
            item.component = new ComponentName(pkg, ri.activityInfo.name);
            CharSequence label = ri.loadLabel(pm);
            item.label = label != null ? label.toString() : pkg;
            item.icon = ri.loadIcon(pm);
            map.put(pkg, item);
        }
    }

    private void applyItems(List<Item> apps, Set<String> hidden) {
        int previousSelection = grid.getSelectedItemPosition();

        items.clear();

        Item settings = new Item();
        settings.type = TYPE_SETTINGS;
        settings.label = "Pengaturan";
        settings.icon = getResources().getDrawable(android.R.drawable.ic_menu_preferences, getTheme());
        items.add(settings);

        int hiddenCount = 0;
        for (Item app : apps) {
            app.hidden = hidden.contains(app.pkg);
            if (app.hidden) {
                hiddenCount++;
                if (!showHidden) {
                    continue;
                }
            }
            items.add(app);
        }

        if (hiddenCount > 0 || showHidden) {
            Item toggle = new Item();
            toggle.type = TYPE_TOGGLE_HIDDEN;
            toggle.label = showHidden ? "Sembunyikan lagi" : "Tersembunyi (" + hiddenCount + ")";
            toggle.icon = getResources().getDrawable(android.R.drawable.ic_menu_view, getTheme());
            items.add(toggle);
        }

        adapter.notifyDataSetChanged();
        if (previousSelection >= 0 && items.size() > 0) {
            grid.setSelection(Math.min(previousSelection, items.size() - 1));
        }
    }

    // ------------------------------------------------------------------
    // Aksi
    // ------------------------------------------------------------------

    private void onItemActivated(Item item) {
        switch (item.type) {
            case TYPE_SETTINGS:
                safeStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case TYPE_TOGGLE_HIDDEN:
                showHidden = !showHidden;
                loadApps();
                break;
            default:
                launch(item);
                break;
        }
    }

    private void launch(Item item) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(item.component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(intent);
    }

    private void safeStart(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("Tidak bisa membuka ini di TV");
        } catch (SecurityException e) {
            toast("Tidak diizinkan membuka ini");
        }
    }

    private void showOptions(final Item item) {
        final CharSequence[] options = new CharSequence[]{
                "Buka",
                "Info aplikasi (nonaktifkan / copot)",
                item.hidden ? "Tampilkan lagi di layar" : "Sembunyikan dari layar"
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(item.label)
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            launch(item);
                        } else if (which == 1) {
                            openAppInfo(item);
                        } else {
                            toggleHidden(item);
                        }
                    }
                })
                .show();
    }

    private void openAppInfo(Item item) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", item.pkg, null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("Buka Pengaturan > Aplikasi secara manual");
        } catch (SecurityException e) {
            toast("Buka Pengaturan > Aplikasi secara manual");
        }
    }

    private void toggleHidden(Item item) {
        Set<String> hidden = new HashSet<String>(
                prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        if (item.hidden) {
            hidden.remove(item.pkg);
        } else {
            hidden.add(item.pkg);
        }
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        loadApps();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class ItemAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            View view = convertView;
            if (view == null) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(8), dp(10), dp(8), dp(10));
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(124)));

                holder = new Holder();

                holder.icon = new ImageView(MainActivity.this);
                holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(holder.icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

                holder.label = new TextView(MainActivity.this);
                holder.label.setTextColor(Color.WHITE);
                holder.label.setTextSize(14);
                holder.label.setGravity(Gravity.CENTER);
                holder.label.setSingleLine(true);
                holder.label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                cell.addView(holder.label, lp);

                cell.setTag(holder);
                view = cell;
            } else {
                holder = (Holder) view.getTag();
            }

            Item item = items.get(position);
            holder.icon.setImageDrawable(item.icon);
            holder.label.setText(item.label);
            view.setAlpha(item.hidden ? 0.45f : 1f);
            return view;
        }
    }
}
