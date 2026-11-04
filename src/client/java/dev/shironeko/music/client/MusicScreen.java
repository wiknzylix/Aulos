package dev.shironeko.music.client;

import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class MusicScreen extends Screen {
  private final Screen parent;
  private String provider = "netease",
      view = "QUEUE",
      query = "",
      playlistTitle = "",
      pendingRepeat = "";
  private List<TrackRef> tracks = new ArrayList<>();
  private List<MusicProvider.Playlist> playlists = new ArrayList<>();
  private List<TrackRef> filteredTracks = List.of();
  private List<QueueEntry> filteredQueue = List.of();
  private List<MusicProvider.Playlist> filteredLists = List.of();
  private final Set<String> selected = new LinkedHashSet<>();
  private final Set<UUID> selectedQueue = new LinkedHashSet<>();
  private final SmoothScroll scroll = new SmoothScroll();
  private EditBox search;
  private FlatButton modeButton, repeatButton, pauseButton;
  private final List<FlatButton> actions = new ArrayList<>();
  private int contentX, contentWidth, top = 42, bottom, total, page;
  private volatile long generation;
  private boolean busy, more, recording, dragging;
  private int apiPage = 1;
  private double dragY, dragOffset;
  private long revision = -1, repeatAt, lastProbe;
  private int probeRow;
  private long shownAccounts = -1;
  private String currentMode = "";
  private static final int ROW = 16;

  private record Cache(long time, List<?> items) {}

  private static final Map<String, Cache> CACHE = new LinkedHashMap<>(16, .75f, true);

  public static void clearCache() {
    CACHE.clear();
  }

  public MusicScreen(Screen parent) {
    super(Component.literal("Aulos"));
    this.parent = parent;
    provider = MusicClient.config.preferredPlatform(MusicClient.providers);
  }

  private MusicProvider provider() {
    return MusicClient.providers.get(provider);
  }

  private FlatButton button(int x, int y, int w, String label, Runnable action) {
    FlatButton b =
        addRenderableWidget(
            ((label.startsWith("立即播放") || label.startsWith("清空队列"))
                ? new ConfirmButton(x, y, w, label, action)
                : new FlatButton(x, y, w, label, action)));
    b.setHeight(Math.clamp((height - 44) / 10, 13, 19) - 2);
    return b;
  }

  private String platformName() {
    return provider().name();
  }

  private String repeat() {
    SessionSnapshot s = MusicClient.state();
    return !pendingRepeat.isEmpty() ? pendingRepeat : s == null ? "LIST" : s.repeat();
  }

  private static String repeatName(String mode) {
    return switch (mode) {
      case "ONE" -> "单曲循环";
      case "SHUFFLE" -> "随机播放";
      default -> "列表循环";
    };
  }

  @Override
  protected void init() {
    clearWidgets();
    if (shownAccounts != MusicClient.accountsRevision) {
      shownAccounts = MusicClient.accountsRevision;
      ++generation;
      busy = false;
      playlists = new ArrayList<>();
      selected.clear();
      if (view.equals("TRACKS") || view.equals("PLAYLISTS")) {
        tracks = new ArrayList<>();
        view = "PLAYLISTS";
        query = "";
        scroll.reset();
      }
    }
    if (!provider.equals(MusicClient.config.lastPlatform)
        && MusicClient.providers.containsKey(MusicClient.config.lastPlatform)
        && MusicClient.providers.get(MusicClient.config.lastPlatform).loggedIn()) {
      provider = MusicClient.config.lastPlatform;
      ++generation;
      busy = false;
      tracks = new ArrayList<>();
      playlists = new ArrayList<>();
      selected.clear();
      query = "";
      scroll.reset();
      if (!view.equals("QUEUE")) view = "SEARCH";
    }
    actions.clear();
    int side = Math.clamp(width / 5, 100, 148), w = side - 16;
    contentX = side + 10;
    contentWidth = width - contentX - 10;
    top = 42;
    bottom = height - 63;
    int navStep = Math.clamp((height - 44) / 10, 13, 19);
    int y = 25;
    modeButton = button(8, y, w, "模式：" + MusicClient.mode(), MusicClient::toggleMode);
    y += navStep;
    button(8, y, w, platformName(), this::cyclePlatform)
        .setTooltip(Tooltip.create(Component.literal("点击切换音乐平台")));
    y += navStep;
    button(8, y, w, "播放队列", () -> show("QUEUE"));
    y += navStep;
    button(8, y, w, "搜索歌曲", () -> show("SEARCH"));
    y += navStep;
    button(8, y, w, "我的歌单", this::loadPlaylists);
    y += navStep;
    button(8, y, w, recording ? "录制按键中…" : "设置", this::settings);
    // Native playback controls form a separate group below navigation.
    int controls = y + navStep + 10, controlStep = navStep + 2;
    int mini = (w - 6) / 3;
    button(8, controls, mini, "|◀", () -> MusicClient.op("previous"))
        .setTooltip(Tooltip.create(Component.literal("上一首")));
    pauseButton = button(11 + mini, controls, mini, "∥", () -> MusicClient.op("toggle"));
    pauseButton.setTooltip(Tooltip.create(Component.literal("播放 / 暂停")));
    button(14 + 2 * mini, controls, mini, "▶|", () -> MusicClient.op("next"))
        .setTooltip(Tooltip.create(Component.literal("切歌：直接播放下一首")));
    button(8, controls + controlStep, w, "音量", () -> minecraft.setScreen(new VolumeScreen(this)));
    repeatButton =
        button(8, controls + 2 * controlStep, w, repeatName(repeat()), this::cycleRepeat);
    button(8, height - 24, w, "返回游戏", () -> minecraft.setScreen(null));
    search =
        addRenderableWidget(
            new EditBox(font, contentX, 8, contentWidth - 48, 18, Component.literal("搜索歌名或歌手")));
    search.setHint(Component.literal(view.equals("SEARCH") ? "搜索当前平台" : "搜索歌名 / 歌手"));
    search.setMaxLength(200);
    search.setValue(query);
    search.setResponder(
        value -> {
          query = value;
          if (!view.equals("SEARCH")) {
            scroll.reset();
            refreshRows();
          }
        });
    button(
        contentX + contentWidth - 44,
        8,
        44,
        "搜索",
        () -> {
          if (view.equals("SEARCH")) loadSearch(false);
          else refreshRows();
        });
    buildActions();
    refreshRows();
    updateLabels();
  }

  private void updateLabels() {
    modeButton.setMessage(Component.literal("模式：" + MusicClient.mode()));
    SessionSnapshot s = MusicClient.state();
    if (!pendingRepeat.isEmpty()
        && (s != null && s.repeat().equals(pendingRepeat) || MusicClient.now() - repeatAt > 5000))
      pendingRepeat = "";
    repeatButton.setMessage(Component.literal(repeatName(repeat())));
    pauseButton.setMessage(
        Component.literal(s != null && s.timeline().status().equals("PLAYING") ? "∥" : "▶"));
    currentMode = MusicClient.mode();
    revision = s == null ? -1 : s.revision();
  }

  private void cycleRepeat() {
    String next =
        switch (repeat()) {
          case "LIST" -> "SHUFFLE";
          case "SHUFFLE" -> "ONE";
          default -> "LIST";
        };
    pendingRepeat = next;
    repeatAt = MusicClient.now();
    repeatButton.setMessage(Component.literal(repeatName(next)));
    MusicClient.op("repeat", Map.of("mode", next));
  }

  private void show(String next) {
    generation++;
    busy = false;
    more = false;
    view = next;
    query = "";
    scroll.reset();
    init();
  }

  private void cyclePlatform() {
    var ids = new ArrayList<>(MusicClient.providers.keySet());
    provider = ids.get((ids.indexOf(provider) + 1) % ids.size());
    MusicClient.config.lastPlatform = provider;
    MusicClient.config.save(MusicClient.SETTINGS);
    selected.clear();
    tracks = new ArrayList<>();
    playlists = new ArrayList<>();
    String previous = view;
    show(view.equals("TRACKS") ? "PLAYLISTS" : view);
    if (previous.equals("PLAYLISTS") || previous.equals("TRACKS")) loadPlaylists();
  }

  private void settings() {
    minecraft.setScreen(new SettingsScreen(this));
  }

  private String key(TrackRef t) {
    return t.platform() + ":" + t.id();
  }

  private boolean match(TrackRef t) {
    return (t.title() + " " + t.artist())
        .toLowerCase(Locale.ROOT)
        .contains(query.toLowerCase(Locale.ROOT));
  }

  private void refreshRows() {
    SessionSnapshot state = MusicClient.state();
    filteredQueue =
        state == null ? List.of() : state.queue().stream().filter(e -> match(e.track())).toList();
    filteredTracks = view.equals("SEARCH") ? tracks : tracks.stream().filter(this::match).toList();
    filteredLists =
        playlists.stream()
            .filter(
                p -> p.title().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
            .toList();
    total =
        view.equals("QUEUE")
            ? filteredQueue.size()
            : view.equals("PLAYLISTS") ? filteredLists.size() : filteredTracks.size();
    scroll.limit(total * ROW - (bottom - top));
    if (state != null) selectedQueue.retainAll(state.queue().stream().map(QueueEntry::id).toList());
    for (FlatButton b : actions) {
      String label = b.getMessage().getString();
      b.active =
          label.startsWith("随机") ? view.equals("TRACKS") && !busy : !(busy && label.equals("全部加入"));
    }
  }

  private List<UUID> ids() {
    return filteredQueue.stream().map(QueueEntry::id).filter(selectedQueue::contains).toList();
  }

  private List<TrackRef> songs() {
    return tracks.stream().filter(t -> selected.contains(key(t))).distinct().toList();
  }

  private void buildActions() {
    if (view.equals("PLAYLISTS")) return;
    List<ActionScreen.Action> list = new ArrayList<>();
    list.add(
        new ActionScreen.Action(
            "全选",
            () -> {
              if (view.equals("QUEUE")) filteredQueue.forEach(e -> selectedQueue.add(e.id()));
              else filteredTracks.forEach(t -> selected.add(key(t)));
            }));
    list.add(
        new ActionScreen.Action(
            "清除勾选",
            () -> {
              selected.clear();
              selectedQueue.clear();
            }));
    if (view.equals("QUEUE")) {
      list.add(
          new ActionScreen.Action(
              "下一首播放", () -> MusicClient.op("prioritize", Map.of("ids", ids()))));
      list.add(new ActionScreen.Action("移除", () -> MusicClient.op("remove", Map.of("ids", ids()))));
      list.add(
          new ActionScreen.Action(
              "立即播放…",
              () -> {
                if (ids().size() == 1) {
                  UUID id = ids().getFirst();
                  MusicClient.op("play", Map.of("id", id.toString()));
                } else MusicClient.message("请选择一首歌曲");
              }));
      list.add(new ActionScreen.Action("清空队列…", () -> MusicClient.op("clear")));
    } else {
      list.add(new ActionScreen.Action("加入队列", () -> MusicClient.add(songs(), false)));
      list.add(new ActionScreen.Action("下一首播放", () -> MusicClient.add(songs(), true)));
      for (int count : List.of(10, 50, 100))
        list.add(new ActionScreen.Action("随机 " + count, () -> random(count)));
      list.add(
          new ActionScreen.Action(
              "全部加入", () -> MusicClient.add(List.copyOf(filteredTracks), false)));
    }
    int cols = (list.size() + 1) / 2, gap = 3, w = (contentWidth - gap * (cols - 1)) / cols;
    for (int i = 0; i < list.size(); i++) {
      var a = list.get(i);
      FlatButton b =
          button(
              contentX + (i % cols) * (w + gap),
              height - 43 + (i / cols) * 20,
              w,
              a.text(),
              a.run());
      b.setHeight(17);
      if (a.text().startsWith("随机 ")) b.active = view.equals("TRACKS") && !busy;
      actions.add(b);
    }
  }

  private void random(int count) {
    if (!view.equals("TRACKS") || busy) {
      MusicClient.message("仅能在加载完成的歌单内随机添加");
      return;
    }
    Map<String, TrackRef> unique = new LinkedHashMap<>();
    filteredTracks.forEach(t -> unique.put(key(t), t));
    List<TrackRef> pool = new ArrayList<>(unique.values());
    Collections.shuffle(pool);
    MusicClient.add(pool.subList(0, Math.min(count, pool.size())), false);
  }

  private void menu(Object target) {
    List<ActionScreen.Action> list = new ArrayList<>();
    if (target instanceof QueueEntry e) {
      list.add(
          new ActionScreen.Action(
              "下一首播放",
              () -> {
                MusicClient.op("prioritize", Map.of("ids", List.of(e.id())));
                minecraft.setScreen(this);
              }));
      list.add(
          new ActionScreen.Action(
              "立即播放…", () -> MusicClient.op("play", Map.of("id", e.id().toString()))));
      list.add(
          new ActionScreen.Action(
              "从队列移除",
              () -> {
                MusicClient.op("remove", Map.of("ids", List.of(e.id())));
                minecraft.setScreen(this);
              }));
    }
    if (target instanceof TrackRef t) {
      list.add(
          new ActionScreen.Action(
              "加入队列",
              () -> {
                MusicClient.add(List.of(t), false);
                minecraft.setScreen(this);
              }));
      list.add(
          new ActionScreen.Action(
              "下一首播放",
              () -> {
                MusicClient.add(List.of(t), true);
                minecraft.setScreen(this);
              }));
    }
    list.add(new ActionScreen.Action("取消", () -> minecraft.setScreen(this)));
    minecraft.setScreen(
        new ActionScreen(
            this,
            target instanceof QueueEntry e ? e.track().label() : ((TrackRef) target).label(),
            list));
  }

  private Cache cached(String key) {
    Cache cache = CACHE.get(key);
    return cache != null && MusicClient.now() - cache.time < 120000 ? cache : null;
  }

  private void cache(String key, List<?> values) {
    CACHE.put(key, new Cache(MusicClient.now(), List.copyOf(values)));
    while (CACHE.size() > 8) CACHE.remove(CACHE.keySet().iterator().next());
  }

  @SuppressWarnings("unchecked")
  private void loadPlaylists() {
    show("PLAYLISTS");
    selected.clear();
    Cache cached = cached(provider + ":lists");
    if (cached != null) {
      playlists = new ArrayList<>((List<MusicProvider.Playlist>) cached.items);
      refreshRows();
      return;
    }
    playlists = new ArrayList<>();
    busy = true;
    refreshRows();
    long gen = ++generation;
    MusicProvider p = provider();
    String cacheKey = provider + ":lists";
    MusicClient.async(
        () -> {
          try {
            List<MusicProvider.Playlist> all = new ArrayList<>();
            for (int pg = 1; pg <= 200 && gen == generation; pg++) {
              var result = p.playlists(pg);
              all.addAll(result.items());
              List<MusicProvider.Playlist> batch = List.copyOf(all);
              minecraft.execute(
                  () -> {
                    if (gen == generation) {
                      playlists = batch;
                      refreshRows();
                    }
                  });
              if (!result.more() || result.items().isEmpty()) break;
            }
            minecraft.execute(
                () -> {
                  if (gen == generation) cache(cacheKey, playlists);
                });
            return true;
          } finally {
            minecraft.execute(
                () -> {
                  if (gen == generation) {
                    busy = false;
                    refreshRows();
                  }
                });
          }
        },
        v -> {});
  }

  @SuppressWarnings("unchecked")
  private void loadTracks(MusicProvider.Playlist list) {
    show("TRACKS");
    playlistTitle = list.title();
    selected.clear();
    Cache cached = cached(provider + ":" + list.id());
    if (cached != null) {
      tracks = new ArrayList<>((List<TrackRef>) cached.items);
      refreshRows();
      return;
    }
    tracks = new ArrayList<>();
    busy = true;
    refreshRows();
    long gen = ++generation;
    MusicProvider p = provider();
    String cacheKey = provider + ":" + list.id();
    MusicClient.async(
        () -> {
          try {
            List<TrackRef> all = new ArrayList<>();
            for (int pg = 1; pg <= 200 && gen == generation; pg++) {
              var result = p.tracks(list.id(), pg);
              all.addAll(result.items());
              if (all.size() > 10000) throw new java.io.IOException("歌单超过10000首");
              List<TrackRef> batch = List.copyOf(all);
              minecraft.execute(
                  () -> {
                    if (gen == generation) {
                      tracks = batch;
                      refreshRows();
                    }
                  });
              if (!result.more() || result.items().isEmpty()) break;
            }
            minecraft.execute(
                () -> {
                  if (gen == generation) cache(cacheKey, tracks);
                });
            return true;
          } finally {
            minecraft.execute(
                () -> {
                  if (gen == generation) {
                    busy = false;
                    refreshRows();
                  }
                });
          }
        },
        v -> {});
  }

  private void loadSearch(boolean append) {
    if (busy || query.isBlank() || append && !more) return;
    busy = true;
    long gen = ++generation;
    MusicProvider p = provider();
    String term = query;
    int pg = append ? apiPage + 1 : 1;
    MusicClient.async(
        () -> {
          try {
            return p.search(term, pg);
          } finally {
            minecraft.execute(
                () -> {
                  if (gen == generation) {
                    busy = false;
                    refreshRows();
                  }
                });
          }
        },
        result -> {
          if (gen != generation) return;
          if (append) tracks.addAll(result.items());
          else {
            tracks = new ArrayList<>(result.items());
            scroll.reset();
            selected.clear();
          }
          apiPage = pg;
          more = result.more();
          refreshRows();
        });
  }

  @Override
  public void tick() {
    if (view.equals("QUEUE")
        && RelayClient.sources != null
        && MusicClient.now() - lastProbe > 1200
        && !filteredQueue.isEmpty()) {
      int first = (int) (scroll.value() / ROW),
          count = Math.min(filteredQueue.size() - first, Math.max(1, (bottom - top) / ROW));
      if (count > 0) {
        RelayClient.sources.prefetch(filteredQueue.get(first + (probeRow++ % count)).track());
        lastProbe = MusicClient.now();
      }
    }
    for (FlatButton b : actions)
      if (b.getMessage().getString().startsWith("随机 ")) b.active = view.equals("TRACKS") && !busy;
    SessionSnapshot s = MusicClient.state();
    if (!currentMode.equals(MusicClient.mode()) || (s == null ? -1 : s.revision()) != revision) {
      refreshRows();
      updateLabels();
    } else if (!pendingRepeat.isEmpty()) updateLabels();
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    for (var child : children())
      if (child instanceof ConfirmButton b && !b.isMouseOver(e.x(), e.y())) b.cancel();
    if (e.button() == 0
        && total * ROW > bottom - top
        && e.x() >= contentX + contentWidth - 7
        && e.y() >= top
        && e.y() < bottom) {
      dragging = true;
      dragY = e.y();
      dragOffset = scroll.value();
      double thumb = Math.max(16, (bottom - top) * (double) (bottom - top) / (total * ROW));
      double at =
          top + (bottom - top - thumb) * scroll.value() / Math.max(1, total * ROW - bottom + top);
      if (e.y() < at || e.y() > at + thumb) {
        scroll.jump(
            (e.y() - top - thumb / 2)
                * (total * ROW - bottom + top)
                / Math.max(1, bottom - top - thumb));
        dragOffset = scroll.value();
      }
      return true;
    }
    if (e.x() >= contentX
        && e.x() < contentX + contentWidth - 7
        && e.y() >= top
        && e.y() < bottom) {
      int index = (int) ((e.y() - top + scroll.value()) / ROW);
      if (index >= total) return true;
      if (view.equals("PLAYLISTS")) {
        if (e.button() == 0) loadTracks(filteredLists.get(index));
        return true;
      }
      Object target = view.equals("QUEUE") ? filteredQueue.get(index) : filteredTracks.get(index);
      if (e.button() == 1) menu(target);
      else if (e.button() == 0) {
        if (target instanceof QueueEntry q) {
          if (!selectedQueue.add(q.id())) selectedQueue.remove(q.id());
        } else {
          String key = key((TrackRef) target);
          if (!selected.add(key)) selected.remove(key);
        }
      }
      return true;
    }
    return super.mouseClicked(e, twice);
  }

  @Override
  public boolean mouseScrolled(double x, double y, double h, double v) {
    if (x >= contentX && y >= top && y < bottom) {
      scroll.move(-v * ROW * 3);
      if (view.equals("SEARCH")
          && more
          && !busy
          && scroll.target() + bottom - top >= total * ROW - ROW * 2) loadSearch(true);
      return true;
    }
    return super.mouseScrolled(x, y, h, v);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (dragging) {
      double thumb = Math.max(16, (bottom - top) * (double) (bottom - top) / (total * ROW));
      scroll.jump(
          dragOffset
              + (e.y() - dragY) * (total * ROW - bottom + top) / Math.max(1, bottom - top - thumb));
      return true;
    }
    return super.mouseDragged(e, dx, dy);
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent e) {
    if (dragging) {
      dragging = false;
      return true;
    }
    return super.mouseReleased(e);
  }

  @Override
  public boolean keyPressed(KeyEvent e) {
    if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
      boolean cancelled = false;
      for (var child : children()) if (child instanceof ConfirmButton b) cancelled |= b.cancel();
      if (cancelled) return true;
    }
    if (recording) {
      if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
        recording = false;
        init();
        return true;
      }
      if (e.key() >= GLFW.GLFW_KEY_LEFT_SHIFT && e.key() <= GLFW.GLFW_KEY_RIGHT_SUPER) return true;
      MusicClient.config.key = e.key();
      MusicClient.config.modifiers = e.modifiers() & 7;
      recording = false;
      MusicClient.config.save(MusicClient.SETTINGS);
      for (var binding : minecraft.options.keyMappings)
        if (binding.matches(e)) MusicClient.message("按键冲突：" + binding.getName());
      init();
      return true;
    }
    if (e.key() == GLFW.GLFW_KEY_ENTER && search.isFocused()) {
      if (view.equals("SEARCH")) loadSearch(false);
      else refreshRows();
      return true;
    }
    return super.keyPressed(e);
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float dt) {
    ConfirmButton.expire(children());
    scroll.animate();
    page = (int) (scroll.value() / ROW);
    String version = dev.shironeko.music.MusicTogether.VERSION;
    float versionScale = .7f;
    int brandWidth = font.width("Aulos"), gap = font.width(" ");
    int brandX =
        Math.round(((contentX - 10) - brandWidth - gap - font.width(version) * versionScale) / 2);
    g.drawString(font, "Aulos", brandX, 9, 0xFFFFFFFF, false);
    g.pose().pushMatrix();
    g.pose().translate(brandX + brandWidth + gap, 12);
    g.pose().scale(versionScale, versionScale);
    g.drawString(font, version, 0, 0, 0xFFAAAAAA, false);
    g.pose().popMatrix();
    int controls = pauseButton.getY();
    g.fill(7, controls - 6, contentX - 14, controls - 5, 0xFFAAAAAA);
    String caption =
        view.equals("QUEUE")
            ? "播放队列"
            : view.equals("PLAYLISTS")
                ? "我的歌单"
                : view.equals("TRACKS") ? playlistTitle : platformName() + " 搜索";
    Marquee.draw(g, font, caption, contentX, 30, contentWidth - 75, 0xFFFFFFFF);
    if (busy) g.drawString(font, "正在加载…", contentX + contentWidth - 65, 30, 0xFFAAAAAA, false);
    g.fill(contentX - 1, top - 1, contentX + contentWidth + 1, bottom + 1, 0xB0000000);
    g.enableScissor(contentX, top, contentX + contentWidth, bottom);
    SessionSnapshot state = MusicClient.state();
    int first = (int) (scroll.value() / ROW),
        last = Math.min(total, (int) Math.ceil((scroll.value() + bottom - top) / ROW));
    for (int i = first; i < last; i++) {
      String text, duration;
      int dot = 0;
      boolean chosen = false, playing = false;
      if (view.equals("PLAYLISTS")) {
        var p = filteredLists.get(i);
        text = p.title();
        duration = p.count() + " 首";
      } else {
        TrackRef t;
        if (view.equals("QUEUE")) {
          var e = filteredQueue.get(i);
          t = e.track();
          if (RelayClient.needsRelay(e))
            dot =
                t.platform().equals("netease")
                    ? 0xFFFF5555
                    : t.platform().equals("qq") ? 0xFF55FF55 : 0xFF5599FF;
          chosen = selectedQueue.contains(e.id());
          playing = state != null && e.id().equals(state.current());
        } else {
          t = filteredTracks.get(i);
          chosen = selected.contains(key(t));
        }
        text = t.label();
        duration = MusicHud.time(t.durationMs());
      }
      int y = top + i * ROW - (int) scroll.value();
      boolean hover =
          mx >= contentX
              && mx < contentX + contentWidth
              && my >= Math.max(y, top)
              && my < Math.min(bottom, y + ROW);
      if (chosen || hover)
        g.fill(contentX, y, contentX + contentWidth - 7, y + ROW, chosen ? 0x90666666 : 0x50444444);
      if (chosen) g.renderOutline(contentX, y, contentWidth - 7, ROW, 0xFFFFFFFF);
      String number =
          playing
              ? (state.timeline().status().equals("PREPARING")
                      ? "… "
                      : state.timeline().status().equals("PAUSED") ? "∥ " : "▶ ")
                  + (i + 1)
              : Integer.toString(i + 1);
      g.drawString(font, number, contentX + 3, y + 4, playing ? 0xFFFFFF55 : 0xFFAAAAAA, false);
      int left = Math.max(28, font.width(Integer.toString(total)) + 16),
          right = font.width(duration);
      g.drawString(font, duration, contentX + contentWidth - right - 11, y + 4, 0xFFAAAAAA, false);
      if (dot != 0)
        g.fill(
            contentX + contentWidth - right - 18,
            y + 6,
            contentX + contentWidth - right - 15,
            y + 9,
            dot);
      Marquee.draw(
          g,
          font,
          text,
          contentX + left,
          y + 4,
          contentWidth - left - right - (dot == 0 ? 18 : 24),
          playing ? 0xFFFFFF55 : 0xFFFFFFFF);
    }
    g.disableScissor();
    if (total == 0)
      g.drawCenteredString(
          font, busy ? "正在加载…" : "暂无内容", contentX + contentWidth / 2, top + 16, 0xFFAAAAAA);
    if (total * ROW > bottom - top) {
      int thumb = Math.max(16, (bottom - top) * (bottom - top) / (total * ROW)),
          at =
              top
                  + (int)
                      ((bottom - top - thumb)
                          * scroll.value()
                          / Math.max(1, total * ROW - bottom + top));
      g.fill(contentX + contentWidth - 6, top, contentX + contentWidth, bottom, 0xFF000000);
      g.fill(contentX + contentWidth - 6, at, contentX + contentWidth, at + thumb, 0xFFC0C0C0);
      g.fill(
          contentX + contentWidth - 5,
          at + 1,
          contentX + contentWidth - 1,
          at + thumb - 1,
          0xFF808080);
    }
    String count =
        Math.min(total, (int) Math.ceil((scroll.value() + bottom - top) / ROW)) + " / " + total;
    g.drawString(font, count, contentX, bottom + 4, 0xFFAAAAAA, false);
    // Account name belongs in Settings, never below a playlist.
    String feedback = MusicClient.feedback;
    int feedbackColour = MusicClient.feedbackColour;
    if (feedback.isEmpty() && state != null && state.timeline().status().equals("PREPARING")) {
      feedback = "正在缓冲，等待统一开播…";
      feedbackColour = 0xFFAAAAAA;
    } else if (feedback.isEmpty()
        && MusicClient.audio != null
        && !MusicClient.audio.status.isEmpty()) {
      feedback = MusicClient.audio.status;
      feedbackColour = feedback.contains("失败") ? 0xFFFF5555 : 0xFFAAAAAA;
    }
    if (!feedback.isEmpty())
      Marquee.draw(
          g,
          font,
          feedback,
          contentX + font.width(count) + 8,
          bottom + 4,
          contentWidth - font.width(count) - 8,
          feedbackColour);
    super.render(g, mx, my, dt);
  }

  @Override
  public void onClose() {
    MusicClient.config.save(MusicClient.SETTINGS);
    minecraft.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
