package dev.shironeko.music.client;

import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.mojang.blaze3d.platform.NativeImage;
import dev.shironeko.music.provider.MusicProvider;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class QrScreen extends Screen {
  private final Screen parent;
  private final MusicProvider provider;
  private MusicProvider.Qr qr;
  private Identifier image;
  private boolean busy = false, done = false, expired = false;
  private long nextPoll, generation;
  private String status = "正在获取二维码…";
  private boolean wechat;
  private java.util.concurrent.Future<?> task;

  public QrScreen(Screen parent, MusicProvider provider) {
    super(Component.literal(provider.name() + " 扫码登录"));
    this.parent = parent;
    this.provider = provider;
  }

  public QrScreen(Screen parent, MusicProvider provider, boolean wechat) {
    this(parent, provider);
    this.wechat = wechat;
  }

  protected void init() {
    clearWidgets();
    int y = height - 36;
    if (provider instanceof dev.shironeko.music.provider.QQ) {
      addRenderableWidget(
          new FlatButton(
              width / 2 - 105, 30, 100, wechat ? "QQ 登录" : "[QQ 登录]", () -> switchLogin(false)));
      addRenderableWidget(
          new FlatButton(
              width / 2 + 5, 30, 100, wechat ? "[微信登录]" : "微信登录", () -> switchLogin(true)));
    }
    addRenderableWidget(new FlatButton(width / 2 - 105, y, 100, "刷新二维码", this::refresh));
    addRenderableWidget(new FlatButton(width / 2 + 5, y, 100, "返回", this::onClose));
    if (qr == null && !busy) refresh();
  }

  private void switchLogin(boolean wx) {
    if (wechat == wx) return;
    wechat = wx;
    refresh();
    init();
  }

  private void refresh() {
    if (task != null) task.cancel(true);
    provider.cancelLogin(qr);
    qr = null;
    if (image != null) {
      minecraft.getTextureManager().release(image);
      image = null;
    }
    busy = true;
    done = false;
    expired = false;
    status = "正在获取二维码…";
    long gen = ++generation;
    task =
        MusicClient.async(
            () -> {
              try {
                MusicProvider.Qr value =
                    wechat && provider instanceof dev.shironeko.music.provider.QQ qq
                        ? qq.wechatQr()
                        : provider.qr();
                NativeImage png;
                if (value.image() != null) {
                  // 1.21.11 NativeImage accepts PNG only; WeChat supplies a JPEG QR.
                  byte[] bytes = value.image();
                  if (bytes.length >= 8
                      && bytes[0] == (byte) 0x89
                      && bytes[1] == 'P'
                      && bytes[2] == 'N'
                      && bytes[3] == 'G') png = NativeImage.read(bytes);
                  else {
                    var bitmap =
                        javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                    if (bitmap == null || bitmap.getWidth() > 2048 || bitmap.getHeight() > 2048)
                      throw new java.io.IOException("二维码图片无法解码");
                    var output = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(bitmap, "PNG", output);
                    png = NativeImage.read(output.toByteArray());
                  }
                } else {
                  var matrix =
                      new QRCodeWriter()
                          .encode(
                              value.url(),
                              BarcodeFormat.QR_CODE,
                              256,
                              256,
                              Map.of(EncodeHintType.MARGIN, 4));
                  png = new NativeImage(256, 256, false);
                  for (int y = 0; y < 256; y++)
                    for (int x = 0; x < 256; x++)
                      png.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
                return new Object[] {value, png};
              } finally {
                minecraft.execute(
                    () -> {
                      if (gen == generation) busy = false;
                    });
              }
            },
            result -> {
              NativeImage png = (NativeImage) result[1];
              if (gen != generation || minecraft.screen != this) {
                png.close();
                return;
              }
              if (image != null) minecraft.getTextureManager().release(image);
              image = Identifier.fromNamespaceAndPath("aulos", "qr/" + UUID.randomUUID());
              minecraft
                  .getTextureManager()
                  .register(image, new DynamicTexture(() -> "Aulos login QR", png));
              qr = (MusicProvider.Qr) result[0];
              status =
                  "请使用 "
                      + (provider.id().equals("qq") ? (wechat ? "微信" : "QQ") : provider.name())
                      + " 扫码并确认";
              nextPoll = 0;
            });
  }

  public void tick() {
    if (qr == null || busy || done || expired) return;
    if (System.currentTimeMillis() > qr.expiresAt()) {
      expired = true;
      status = "二维码已过期，请刷新";
      return;
    }
    if (MusicClient.now() < nextPoll) return;
    busy = true;
    nextPoll = MusicClient.now() + 2000;
    long gen = generation;
    MusicProvider.Qr polling = qr;
    task =
        MusicClient.async(
            () -> {
              try {
                return provider.poll(polling);
              } finally {
                minecraft.execute(
                    () -> {
                      if (gen == generation) busy = false;
                    });
              }
            },
            result -> {
              if (gen != generation) return;
              status = result.status();
              done = result.done();
              expired = result.expired();
              if (done) {
                MusicClient.config.lastPlatform = provider.id();
                MusicClient.config.save(MusicClient.SETTINGS);
                MusicClient.accountChanged(provider);
                MusicClient.message(provider.name() + " 登录成功");
              }
            });
  }

  public void render(GuiGraphics g, int x, int y, float dt) {

    g.drawCenteredString(font, title, width / 2, 14, 0xFFE6E9E7);
    int size = Math.min(200, Math.max(60, height - (provider.id().equals("qq") ? 144 : 118)));
    int imageTop = provider.id().equals("qq") ? 57 : 40;
    if (image != null) {
      var texture = (DynamicTexture) minecraft.getTextureManager().getTexture(image);
      NativeImage pixels = texture.getPixels();
      g.blit(
          RenderPipelines.GUI_TEXTURED,
          image,
          (width - size) / 2,
          imageTop,
          0,
          0,
          size,
          size,
          pixels.getWidth(),
          pixels.getHeight(),
          pixels.getWidth(),
          pixels.getHeight());
    }
    g.drawCenteredString(
        font,
        font.plainSubstrByWidth(status, width - 30),
        width / 2,
        imageTop + size + 8,
        done ? 0xFF55FF55 : expired ? 0xFFFF5555 : 0xFFAAAAAA);
    if (!MusicClient.feedback.isEmpty())
      g.drawCenteredString(
          font,
          font.plainSubstrByWidth(MusicClient.feedback, width - 30),
          width / 2,
          imageTop + size + 23,
          MusicClient.feedbackColour);
    super.render(g, x, y, dt);
  }

  public void removed() {
    generation++;
    if (task != null) task.cancel(true);
    provider.cancelLogin(qr);
    if (image != null) {
      minecraft.getTextureManager().release(image);
      image = null;
    }
  }

  public void onClose() {
    minecraft.setScreen(parent);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
