package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;

public final class AulosClientTest implements FabricClientGameTest {
  public void runTest(ClientGameTestContext ctx) {
    ctx.runOnClient(
        c -> {
          MusicClient.config = new ClientConfig();
          for (int[] size :
              List.of(
                  new int[] {320, 180},
                  new int[] {427, 240},
                  new int[] {640, 360},
                  new int[] {1920, 1080})) {
            int[] b = MusicHud.bounds(MusicClient.config.lyrics, size[0], size[1], true);
            if (Math.abs(b[0] + b[2] / 2 - size[0] / 2) > 1
                || b[0] + b[2] > size[0]
                || b[1] + b[3] > size[1])
              throw new AssertionError("Lyrics must stay centered and inside resized screens");
          }
        });
    ctx.setScreen(() -> new MusicScreen(null));
    ctx.runOnClient(
        c -> {
          try {
            var f = MusicScreen.class.getDeclaredField("view");
            f.setAccessible(true);
            if (!f.get(c.screen).equals("QUEUE"))
              throw new AssertionError("Default screen must be queue");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    ctx.runOnClient(
        c -> {
          var original = MusicClient.providers.get("qq");
          var signed =
              (dev.shironeko.music.provider.MusicProvider)
                  java.lang.reflect.Proxy.newProxyInstance(
                      original.getClass().getClassLoader(),
                      new Class[] {dev.shironeko.music.provider.MusicProvider.class},
                      (o, m, args) ->
                          m.getName().equals("loggedIn") ? true : m.invoke(original, args));
          try {
            var current = (MusicScreen) c.screen;
            var tracks = MusicScreen.class.getDeclaredField("tracks");
            tracks.setAccessible(true);
            tracks.set(
                current,
                new java.util.ArrayList<>(
                    java.util.List.of(
                        new TrackRef("netease", "fixture", "old search", "", "", "", 1000))));
            MusicClient.providers.put("qq", signed);
            MusicClient.config.lastPlatform = "qq";
            c.setScreen(current);
            var platform = MusicScreen.class.getDeclaredField("provider");
            platform.setAccessible(true);
            if (!platform.get(current).equals("qq")
                || !((java.util.List<?>) tracks.get(current)).isEmpty())
              throw new AssertionError("Remembered signed-in platform must clear old results");
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            MusicClient.providers.put("qq", original);
            MusicClient.config.lastPlatform = "netease";
          }
        });
    ctx.setScreen(() -> new MusicScreen(null));
    ctx.waitTicks(4);
    ctx.takeScreenshot("aulos-main-menu");
    ctx.clickScreenButton("设置");
    ctx.runOnClient(
        c -> {
          if (!c.screen.getTitle().getString().equals("Aulos " + MusicTogether.VERSION))
            throw new AssertionError("Settings title must show the installed mod version");
          if (c.screen.children().stream()
              .noneMatch(
                  child ->
                      child instanceof net.minecraft.client.gui.components.Button b
                          && b.getMessage().getString().startsWith("打开快捷键")))
            throw new AssertionError("Shortcut label");
        });
    ctx.clickScreenButton("账号管理");
    ctx.takeScreenshot("aulos-account-manager");
    ctx.clickScreenButton("网易云音乐 未登录");
    ctx.clickScreenButton("退出登录");
    ctx.runOnClient(
        c -> {
          if (!(c.screen instanceof AccountScreen)
              || c.screen.children().stream()
                  .noneMatch(
                      w ->
                          w instanceof ConfirmButton b
                              && ((net.minecraft.client.gui.components.Button) b)
                                  .getMessage()
                                  .getString()
                                  .equals("确认退出登录")))
            throw new AssertionError("Logout must confirm in the same button");
          c.screen.onClose();
        });
    ctx.runOnClient(
        c -> {
          if (!(c.screen instanceof AccountScreen)
              || c.screen.children().stream()
                  .noneMatch(
                      w ->
                          w instanceof ConfirmButton b
                              && ((net.minecraft.client.gui.components.Button) b)
                                  .getMessage()
                                  .getString()
                                  .equals("退出登录")))
            throw new AssertionError("Esc must cancel in place");
        });
    ctx.setScreen(() -> new SettingsScreen(null));
    ctx.takeScreenshot("aulos-version-settings");
    ctx.clickScreenButton("打开快捷键：" + MusicClient.keyName());
    ctx.runOnClient(
        c -> {
          if (!(c.screen instanceof SettingsScreen))
            throw new AssertionError("Key recording must stay in settings");
          c.screen.onClose();
        });
    ctx.setScreen(() -> new MusicScreen(null));
    ctx.runOnClient(
        c -> {
          var buttons =
              c.screen.children().stream()
                  .filter(w -> w instanceof net.minecraft.client.gui.components.Button)
                  .map(w -> (net.minecraft.client.gui.components.Button) w)
                  .toList();
          int pause =
              buttons.stream()
                  .filter(b -> Set.of("∥", "▶").contains(b.getMessage().getString()))
                  .findFirst()
                  .orElseThrow()
                  .getY();
          int volume =
              buttons.stream()
                  .filter(b -> b.getMessage().getString().equals("音量"))
                  .findFirst()
                  .orElseThrow()
                  .getY();
          int repeat =
              buttons.stream()
                  .filter(b -> b.getMessage().getString().equals("列表循环"))
                  .findFirst()
                  .orElseThrow()
                  .getY();
          if (!(pause + 17 < volume && volume + 17 < repeat))
            throw new AssertionError("Volume must be between transport and repeat mode");
        });
    ctx.clickScreenButton("搜索歌曲");
    ctx.runOnClient(
        c -> {
          for (var w : c.screen.children())
            if (w instanceof net.minecraft.client.gui.components.Button b
                && b.getMessage().getString().startsWith("随机 ")
                && b.active) throw new AssertionError("Random search actions must be disabled");
        });
    ctx.setScreen(() -> new VolumeScreen(new MusicScreen(null)));
    ctx.clickScreenButton("静音");
    ctx.runOnClient(
        c -> {
          if (!MusicClient.config.muted
              || c.screen.children().stream()
                  .noneMatch(
                      child ->
                          child instanceof net.minecraft.client.gui.components.Button b
                              && b.getMessage().getString().equals("取消静音")))
            throw new AssertionError("Mute button must show current action");
        });
    ctx.clickScreenButton("取消静音");
    ctx.takeScreenshot("aulos-volume");
    if (Boolean.getBoolean("aulos.test.qrUi")) {
      ctx.setScreen(
          () -> new QrScreen(new AccountScreen(null), MusicClient.providers.get("qq"), true));
      ctx.waitFor(
          c -> {
            try {
              var field = QrScreen.class.getDeclaredField("image");
              field.setAccessible(true);
              return field.get(c.screen) != null;
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      ctx.takeScreenshot("aulos-wechat-login");
      ctx.clickScreenButton("QQ 登录");
      ctx.waitFor(
          c -> {
            try {
              var field = QrScreen.class.getDeclaredField("qr");
              field.setAccessible(true);
              var qr = (dev.shironeko.music.provider.MusicProvider.Qr) field.get(c.screen);
              return qr != null && !qr.key().startsWith("wx:");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      ctx.takeScreenshot("aulos-qq-login");
      ctx.runOnClient(c -> c.screen.onClose());
    }
    ctx.setScreen(() -> new HudEditor(new MusicScreen(null)));
    ctx.waitTicks(3);
    ctx.takeScreenshot("aulos-hud-editor");
    ctx.clickScreenButton("隐藏设置");
    ctx.takeScreenshot("aulos-hud-defaults");
    ctx.runOnClient(
        c -> {
          net.minecraft.client.gui.screens.Screen editor = c.screen;
          // Test resizing away from the minimum scale and screen edges, where shrinking is clamped.
          MusicClient.config.card.scale = .8;
          MusicClient.config.card.x = .2;
          MusicClient.config.card.y = .6;
          int[] box =
              MusicHud.bounds(MusicClient.config.card, c.screen.width, c.screen.height, false);
          var info = new net.minecraft.client.input.MouseButtonInfo(0, 0);
          double x = box[0] + box[2] / 2.0, y = box[1] + box[3] / 2.0;
          if (!editor.mouseClicked(
              new net.minecraft.client.input.MouseButtonEvent(x, y, info), false))
            throw new AssertionError("Card interior must be draggable");
          editor.mouseDragged(
              new net.minecraft.client.input.MouseButtonEvent(x + 10, y - 10, info), 10, -10);
          editor.mouseReleased(
              new net.minecraft.client.input.MouseButtonEvent(x + 10, y - 10, info));
          int[] moved =
              MusicHud.bounds(MusicClient.config.card, c.screen.width, c.screen.height, false);
          if (moved[0] == box[0] && moved[1] == box[1])
            throw new AssertionError("Card drag must move bounds");
          box = moved;
          for (int cornerX : new int[] {-1, 0, 1})
            for (int cornerY : new int[] {-1, 0, 1})
              if (cornerX != 0 || cornerY != 0) {
                MusicClient.config.card.scale = .8;
                MusicClient.config.card.x = .2;
                MusicClient.config.card.y = .6;
                box =
                    MusicHud.bounds(
                        MusicClient.config.card, c.screen.width, c.screen.height, false);
                int dx = (cornerX + 1) * box[2] / 2, dy = (cornerY + 1) * box[3] / 2;
                double hx = box[0] + dx, hy = box[1] + dy;
                if (!editor.mouseClicked(
                    new net.minecraft.client.input.MouseButtonEvent(hx, hy, info), false))
                  throw new AssertionError("Every card handle must accept input");
                int[] before =
                    MusicHud.bounds(
                        MusicClient.config.card, c.screen.width, c.screen.height, false);
                editor.mouseDragged(
                    new net.minecraft.client.input.MouseButtonEvent(hx + 2, hy - 2, info), 2, -2);
                int[] after =
                    MusicHud.bounds(
                        MusicClient.config.card, c.screen.width, c.screen.height, false);
                if (java.util.Arrays.equals(before, after))
                  throw new AssertionError("Every card handle must resize");
                editor.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(hx, hy, info));
              }
          MusicClient.config.card = new ClientConfig().card;
        });
    double[] saved =
        ctx.computeOnClient(
            c ->
                new double[] {
                  MusicClient.config.card.x,
                  MusicClient.config.card.y,
                  MusicClient.config.card.scale
                });
    ctx.runOnClient(
        c -> {
          MusicClient.config.card.x = .1;
          MusicClient.config.card.y = 1;
          MusicClient.config.card.scale = .65;
        });
    ctx.takeScreenshot("aulos-card-bottom-border");
    ctx.runOnClient(
        c -> {
          MusicClient.config.card.x = saved[0];
          MusicClient.config.card.y = saved[1];
          MusicClient.config.card.scale = saved[2];
        });
    ctx.takeScreenshot("aulos-centered-and-dragged");
    ctx.clickScreenButton("显示设置");

    ctx.runOnClient(
        c -> {
          for (var child : c.screen.children())
            if (child instanceof EditBox box && box.getMessage().getString().contains("歌词已唱"))
              box.setValue("#FF55AA");
          if (MusicClient.config.lyricActive != 0xFFFF55AA)
            throw new AssertionError("HEX colour must apply immediately");
        });
    ctx.takeScreenshot("aulos-hud-custom-colour");
    ctx.runOnClient(c -> c.screen.onClose());
    try (var world = ctx.worldBuilder().create()) {
      ctx.waitFor(c -> MusicClient.serverAvailable);
      ctx.waitFor(c -> MusicClient.together && MusicClient.state() != null);
      RelayWireTest.run(ctx);
      ShuffleWireTest.run(ctx);
      ctx.setScreen(() -> new MusicScreen(null));
      ctx.clickScreenButton("下一首播放");
      ctx.runOnClient(
          c -> {
            if (!MusicClient.feedback.equals("请选择一首歌曲"))
              throw new AssertionError("Empty selection must show local feedback");
            try {
              var pending = MusicClient.class.getDeclaredField("operations");
              pending.setAccessible(true);
              if (!((java.util.Collection<?>) pending.get(null)).isEmpty())
                throw new AssertionError("Empty selection must not send a shared operation");
            } catch (ReflectiveOperationException e) {
              throw new RuntimeException(e);
            }
          });
      ctx.runOnClient(
          c ->
              MusicClient.add(
                  List.of(
                      new TrackRef("netease", "1", "共听测试歌曲 A", "歌手", "", "", 60000),
                      new TrackRef("qq", "test", "共听测试歌曲 B", "Singer", "", "", 60000)),
                  false));
      ctx.waitFor(c -> MusicClient.state().queue().size() == 2);
      ctx.setScreen(() -> new MusicScreen(null));
      ctx.clickScreenButton("列表循环");
      ctx.runOnClient(
          c -> {
            if (c.screen.children().stream()
                .noneMatch(
                    child ->
                        child instanceof net.minecraft.client.gui.components.Button b
                            && b.getMessage().getString().equals("随机播放")))
              throw new AssertionError("Repeat label must change on first click");
          });
      ctx.waitFor(c -> MusicClient.state().repeat().equals("SHUFFLE"));
      ctx.waitTicks(6);
      ctx.clickScreenButton("随机播放");
      ctx.waitFor(c -> MusicClient.state().repeat().equals("ONE"));
      ctx.runOnClient(
          c -> {
            UUID next = MusicClient.state().queue().getLast().id();
            MusicClient.op("prioritize", Map.of("ids", List.of(next)));
          });
      ctx.waitFor(
          c -> MusicClient.state().next().equals(MusicClient.state().queue().getLast().id()));
      ctx.runOnClient(
          c -> {
            List<TrackRef> batch = new ArrayList<>();
            for (int i = 0; i < 100; i++)
              batch.add(
                  new TrackRef(
                      "netease",
                      "batch-" + i,
                      String.format(Locale.ROOT, "批量曲%03d 用于检查长标题跑马灯与连续滚动", i),
                      "测试歌手",
                      "",
                      "",
                      180000));
            MusicClient.add(batch, false);
          });
      ctx.waitFor(c -> MusicClient.state().queue().size() == 102);
      ctx.setScreen(() -> new MusicScreen(null));
      ctx.clickScreenButton("播放队列");
      ctx.waitTicks(3);
      ctx.takeScreenshot("aulos-shared-queue");
      ctx.runOnClient(c -> c.screen.mouseScrolled(c.screen.width - 20, 100, 0, -100));
      ctx.waitTicks(3);
      ctx.takeScreenshot("aulos-queue-scrolled");
      ctx.runOnClient(
          c -> {
            try {
              var field = MusicScreen.class.getDeclaredField("page");
              field.setAccessible(true);
              if (field.getInt(c.screen) <= 0)
                throw new AssertionError("Wheel must scroll the same list");
            } catch (ReflectiveOperationException e) {
              throw new RuntimeException(e);
            }
            for (var child : c.screen.children())
              if (child instanceof EditBox box) box.setValue("批量曲099");
          });
      ctx.takeScreenshot("aulos-queue-filtered");
      ctx.runOnClient(c -> MusicClient.leave());
      ctx.waitFor(c -> !MusicClient.together);
      ctx.runOnClient(c -> MusicClient.enter(false));
      ctx.waitFor(
          c ->
              MusicClient.together
                  && MusicClient.state() != null
                  && MusicClient.state().queue().size() == 102);
      ctx.setScreen(() -> new MusicScreen(null));
      ctx.clickScreenButton("清空队列…");
      ctx.runOnClient(
          c -> {
            if (!(c.screen instanceof MusicScreen) || MusicClient.state().queue().size() != 102)
              throw new AssertionError("Clear must not run on first click");
          });
      ctx.clickScreenButton("确认清空队列");
      ctx.waitFor(c -> MusicClient.state().queue().isEmpty());
      ctx.runOnClient(c -> MusicClient.leave());
      ctx.waitFor(c -> !MusicClient.together);
    }
    ctx.waitFor(c -> c.level == null);
    ctx.runOnClient(
        c -> {
          if (MusicClient.state() != null)
            throw new AssertionError("Queue must clear on disconnect");
        });
    try {
      java.nio.file.Files.writeString(
          java.nio.file.Path.of("aulos-client-test-passed.txt"),
          "UI colour, 100-track batch, continuous scroll, integrated-server network, priority, mode"
              + " lifecycle: passed\n");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
