package dev.shironeko.music.client;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SEC_OFFSET;
import static org.lwjgl.openal.ALC10.*;

import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.io.*;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javazoom.jl.decoder.*;
import org.lwjgl.openal.*;
import org.lwjgl.system.MemoryUtil;

/**
 * Independent device/context on a single daemon audio thread; never touches Minecraft's context.
 */
public final class AudioEngine implements AutoCloseable {
  record Pcm(float[] samples, int rate, int channels, long frame) {
    int frames() {
      return samples.length / channels;
    }
  }

  private final class Stream implements AutoCloseable {
    final QueueEntry entry;
    final Semaphore memory = new Semaphore(2 * 1024 * 1024);
    volatile String formatLabel = "";
    final ArrayBlockingQueue<Pcm> pcm = new ArrayBlockingQueue<>(2048);
    volatile boolean cancelled, done, ready, failed;
    volatile InputStream input;
    final Set<InputStream> ranges = ConcurrentHashMap.newKeySet();
    volatile Path cache;
    volatile Future<?> job, download;
    volatile AudioSpool spool;
    volatile String error = "";
    volatile int rate = 44100;

    Stream(QueueEntry e) {
      entry = e;
    }

    public synchronized void close() {
      if (cancelled) return;
      cancelled = true;
      if (job != null) job.cancel(true);
      if (download != null) download.cancel(true);
      for (InputStream range : ranges)
        rangeClosers.execute(
            () -> {
              try {
                range.close();
              } catch (IOException ignored) {
              }
            });
      ranges.clear();
      Pcm block;
      while ((block = pcm.poll()) != null) memory.release(block.samples().length * 4);
      try {
        if (spool != null) spool.close();
      } catch (IOException ignored) {
      }
      try {
        if (input != null) input.close();
      } catch (IOException ignored) {
      }
      try {
        if (cache != null) Files.deleteIfExists(cache);
      } catch (IOException ignored) {
      }
    }
  }

  private final ExecutorService decoders =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "Aulos decode");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final ExecutorService rangeClosers =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Aulos range cleanup");
            t.setDaemon(true);
            return t;
          });

  private final Function<String, MusicProvider> providers;
  private final Path cacheRoot;
  private final AudioRepository repository;
  private final boolean ownsRepository;

  @FunctionalInterface
  public interface ReadyCallback {
    void accept(UUID entry, long epoch, boolean ok, long generation);
  }

  private final ReadyCallback prepared;

  private record PlaybackInput(SessionSnapshot state, long offset, double gain, long generation) {}

  private volatile PlaybackInput input = new PlaybackInput(null, 0, .65, 0);
  private SessionSnapshot state;
  private long clockOffset, transportGeneration = Long.MIN_VALUE;
  private UUID activeSession;
  private volatile boolean running = true;
  private double gain = .65;
  private volatile Stream active, preload;
  private final Thread thread;
  private long device, context;
  private int source;
  private final ArrayDeque<Pcm> queued = new ArrayDeque<>();
  private final ArrayDeque<Integer> free = new ArrayDeque<>();
  private final List<Integer> buffers = new ArrayList<>();
  private UUID notified;
  private long prepareEpoch = Long.MIN_VALUE;
  private long playedFrame;
  private int rate = 44100;
  private long lastRetry;
  private String selectedDevice = "";
  private volatile String requestedDevice = "";
  private ALCCapabilities deviceCaps;
  public volatile String status = "", format = "";
  public volatile long preparedAt, firstOutputAt;
  private volatile UUID outputEntry;
  public volatile boolean buffering;

  public long visiblePosition(UUID entry) {
    return Objects.equals(outputEntry, entry) && firstOutputAt > 0
        ? Math.max(0, (long) audiblePositionMs)
        : 0;
  }

  public volatile double audiblePositionMs = 0;
  public volatile double driftMs = 0;
  private long correctionFadeAt, fadeInAt;

  public AudioEngine(Function<String, MusicProvider> providers, ReadyCallback prepared) {
    this(providers, prepared, Path.of("build/audio-cache"));
  }

  public AudioEngine(
      Function<String, MusicProvider> providers, ReadyCallback prepared, Path cacheRoot) {
    this(providers, prepared, cacheRoot, null);
  }

  public AudioEngine(
      Function<String, MusicProvider> providers,
      ReadyCallback prepared,
      Path cacheRoot,
      AudioRepository repository) {
    this.providers = providers;
    this.prepared = prepared;
    this.cacheRoot = cacheRoot;
    this.ownsRepository = repository == null;
    this.repository =
        repository == null
            ? new AudioRepository(
                e -> AudioRepository.direct(providers.apply(e.track().platform()), e),
                null,
                cacheRoot)
            : repository;
    thread = new Thread(this::loop, "Aulos audio");
    thread.setDaemon(true);
    thread.start();
  }

  public void update(SessionSnapshot s, long offset, double gain) {
    update(s, offset, gain, 0);
  }

  public void update(SessionSnapshot s, long offset, double gain, long generation) {
    input = new PlaybackInput(s, offset, gain, generation);
  }

  public void device(String name) {
    requestedDevice = Objects.toString(name, "");
  }

  private Stream decode(QueueEntry entry, long seek) {
    Stream stream = new Stream(entry);
    ((ThreadPoolExecutor) decoders).purge();
    stream.job =
        decoders.submit(
            () -> {
              try {
                AudioRepository.Source encoded = repository.open(entry);
                stream.input = encoded.input();
                long length = encoded.length();
                final long[] buffered = {0};
                AudioDecoder.decode(
                    stream.input,
                    length,
                    encoded.forwarded()
                        ? Math.max(1000, entry.track().durationMs() - 5000)
                        : encoded.durationMs() > 0
                            ? encoded.durationMs()
                            : entry.track().durationMs(),
                    seek,
                    encoded.ranges() == null
                        ? null
                        : position -> {
                          InputStream raw = encoded.ranges().open(position);
                          if (stream.cancelled) {
                            raw.close();
                            throw new InterruptedIOException();
                          }
                          stream.ranges.add(raw);
                          return new FilterInputStream(raw) {
                            public void close() throws IOException {
                              try {
                                super.close();
                              } finally {
                                stream.ranges.remove(raw);
                              }
                            }
                          };
                        },
                    new AudioDecoder.Sink() {
                      public void format(int rate, int channels, int bits, String codec) {
                        stream.rate = rate;
                        stream.formatLabel =
                            codec + " " + bits + " bit / " + (rate / 1000.0) + " kHz";
                      }

                      public void pcm(float[] samples, int rate, int channels, long frame)
                          throws Exception {
                        if (stream.cancelled) throw new InterruptedException();
                        int count = samples.length / channels;
                        long desired = seek;
                        PlaybackInput latest = input;
                        if (active == stream
                            && latest.state() != null
                            && Objects.equals(latest.state().current(), entry.id()))
                          desired =
                              Math.max(
                                  desired,
                                  latest
                                      .state()
                                      .timeline()
                                      .position(
                                          dev.shironeko.music.MusicTogether.now()
                                              + latest.offset()));
                        long skip = Math.clamp(desired * rate / 1000 - frame, 0, count);
                        if (skip == count) return;
                        float[] data =
                            skip == 0
                                ? samples
                                : Arrays.copyOfRange(
                                    samples, (int) skip * channels, samples.length);
                        int bytes = data.length * 4;
                        stream.memory.acquire(bytes);
                        synchronized (stream) {
                          if (stream.cancelled) {
                            stream.memory.release(bytes);
                            throw new InterruptedException();
                          }
                          Pcm pcm = new Pcm(data, rate, channels, frame + skip);
                          if (!stream.pcm.offer(pcm)) {
                            stream.memory.release(bytes);
                            throw new IOException("PCM缓冲已满");
                          }
                        }
                        buffered[0] += data.length / channels;
                        if (buffered[0] >= Math.min(rate * 3 / 4, 1024 * 1024 / (channels * 4)))
                          stream.ready = true;
                      }
                    });
                stream.ready = true;
                stream.done = true;
              } catch (Exception e) {
                if (!stream.cancelled) {
                  providers.apply(entry.track().platform()).invalidateSource(entry.track());
                  stream.failed = true;
                  stream.error =
                      "音频失败："
                          + Objects.toString(e.getMessage(), e.getClass().getSimpleName())
                              .replaceAll("https?://[^ ]+", "[URL]");
                  stream.done = true;
                }
              } finally {
                try {
                  if (stream.input != null) stream.input.close();
                } catch (IOException ignored) {
                }
              }
            });
    return stream;
  }

  private void open() throws Exception {
    selectedDevice = requestedDevice;
    device = alcOpenDevice(selectedDevice.isBlank() ? (CharSequence) null : selectedDevice);
    if (device == 0) throw new IOException("无法打开音频设备");
    ALCCapabilities caps = ALC.createCapabilities(device);
    deviceCaps = caps;
    if (!caps.ALC_EXT_thread_local_context) throw new IOException("音频设备缺少独立线程上下文支持");
    context = alcCreateContext(device, (IntBuffer) null);
    if (context == 0 || !EXTThreadLocalContext.alcSetThreadContext(context))
      throw new IOException("无法建立音乐音频上下文");
    AL.createCapabilities(caps);
    if (!AL.getCapabilities().AL_EXT_FLOAT32) throw new IOException("输出设备不支持原始高精度浮点PCM");
    source = alGenSources();
    for (int i = 0; i < 4; i++) {
      int id = alGenBuffers();
      buffers.add(id);
      free.add(id);
    }
    alSourcef(source, AL_GAIN, (float) gain);
  }

  private void flush() {
    correctionFadeAt = 0;
    fadeInAt = 0;
    if (source == 0) return;
    alSourceStop(source);
    int count = alGetSourcei(source, AL_BUFFERS_QUEUED);
    for (int i = 0; i < count; i++) free.add(alSourceUnqueueBuffers(source));
    queued.clear();
  }

  private void release() {
    if (source != 0) {
      flush();
      alDeleteSources(source);
      for (int id : buffers) alDeleteBuffers(id);
      buffers.clear();
      free.clear();
      source = 0;
    }
    if (context != 0) {
      EXTThreadLocalContext.alcSetThreadContext(0);
      alcDestroyContext(context);
      context = 0;
    }
    if (device != 0) {
      alcCloseDevice(device);
      device = 0;
    }
  }

  private void loop() {
    try {
      while (running) {
        try {
          if (device == 0) open();
          if (!selectedDevice.equals(requestedDevice)) throw new IOException("音频输出设备已切换");
          if (dev.shironeko.music.MusicTogether.now() - lastRetry > 1000) {
            lastRetry = dev.shironeko.music.MusicTogether.now();
            if (deviceCaps.ALC_EXT_disconnect
                && alcGetInteger(device, EXTDisconnect.ALC_CONNECTED) == 0)
              throw new IOException("音频设备已断开");
          }
          step();
          Thread.sleep(5);
        } catch (InterruptedException e) {
          break;
        } catch (Exception e) {
          status = "音频设备重建中：" + e.getClass().getSimpleName();
          if (active != null) {
            active.close();
            active = null;
          }
          if (preload != null) {
            preload.close();
            preload = null;
          }
          SessionSnapshot snapshot = input.state();
          if (snapshot != null
              && snapshot.current() != null
              && !Objects.equals(notified, snapshot.current())) {
            notified = snapshot.current();
            prepared.accept(
                snapshot.current(), snapshot.timeline().epochMs(), false, input.generation());
          }
          release();
          Thread.sleep(2000);
        }
      }
    } catch (Exception e) {
      status = "音乐输出失败：" + e.getMessage();
    } finally {
      if (active != null) active.close();
      if (preload != null) preload.close();
      release();
    }
  }

  private void step() {
    PlaybackInput update = input;
    SessionSnapshot s = update.state();
    state = s;
    gain = update.gain();
    clockOffset = update.offset();
    UUID session = s == null ? null : s.sessionId();
    if (transportGeneration != update.generation() || !Objects.equals(activeSession, session)) {
      transportGeneration = update.generation();
      activeSession = session;
      flush();
      if (active != null) active.close();
      if (preload != null) preload.close();
      active = null;
      preload = null;
      prepareEpoch = Long.MIN_VALUE;
      notified = null;
    }
    QueueEntry entry = s == null ? null : s.currentEntry();
    long now = dev.shironeko.music.MusicTogether.now() + clockOffset;
    if (entry == null || s.timeline().status().equals("STOPPED")) {
      if (active != null) {
        active.close();
        active = null;
      }
      if (preload != null) {
        preload.close();
        preload = null;
      }
      flush();
      notified = null;
      status = "";
      return;
    }
    boolean newPreparation =
        s.timeline().status().equals("PREPARING") && s.timeline().epochMs() != prepareEpoch;
    if (active == null || !active.entry.id().equals(entry.id()) || newPreparation) {
      flush();
      if (active != null) active.close();
      active =
          preload != null && preload.entry.id().equals(entry.id())
              ? preload
              : decode(entry, s.timeline().position(now));
      if (active == preload) preload = null;
      notified = null;
      playedFrame = 0;
      firstOutputAt = 0;
      outputEntry = entry.id();
      audiblePositionMs = 0;
      preparedAt = 0;
      status = "正在缓冲";
      buffering = true;
      if (newPreparation) prepareEpoch = s.timeline().epochMs();
    }
    if (!Objects.equals(notified, entry.id()) && (active.ready || active.failed)) {
      notified = entry.id();
      preparedAt = dev.shironeko.music.MusicTogether.now();
      prepared.accept(entry.id(), prepareEpoch, !active.failed, transportGeneration);
    }
    if (active.failed) {
      flush();
      status = active.error;
      return;
    }
    QueueEntry next = s.nextEntry();
    if (next != null && !next.id().equals(entry.id())) {
      if (preload != null && !preload.entry.id().equals(next.id())) {
        preload.close();
        preload = null;
      }
      if (preload == null) preload = decode(next, 0);
    } else if (preload != null) {
      preload.close();
      preload = null;
    }
    format = active.formatLabel;
    buffering = !active.ready;
    if (!active.ready) {
      alSourceStop(source);
      status = repository.forwarded(entry.id()) ? "正在接收点歌人音频并缓冲" : "正在缓冲";
      return;
    }
    boolean playing =
        s.timeline().status().equals("PLAYING") && now >= s.timeline().epochMs()
            || s.timeline().status().equals("PAUSING") && now < s.timeline().epochMs();
    if (!playing) {
      if (alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING) alSourcePause(source);
      fadeInAt = 0;
      status = s.timeline().status().equals("PREPARING") ? "等待同步开播" : "";
      return;
    }
    int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
    for (int i = 0; i < processed; i++) {
      free.add(alSourceUnqueueBuffers(source));
      Pcm p = queued.poll();
      if (p != null) playedFrame = p.frame() + p.frames();
    }
    long target = s.timeline().position(now);
    double offset = alGetSourcef(source, AL_SEC_OFFSET), latency = 0;
    if (AL.getCapabilities().AL_SOFT_source_latency) {
      double[] values = new double[2];
      SOFTSourceLatency.alGetSourcedvSOFT(
          source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, values);
      offset = values[0];
      latency = values[1];
    }
    audiblePositionMs = Math.max(0, playedFrame * 1000.0 / rate + offset * 1000 - latency * 1000);
    driftMs = audiblePositionMs - target;
    if (!queued.isEmpty() && Math.abs(driftMs) > 180) {
      if (correctionFadeAt == 0) correctionFadeAt = now;
      alSourcef(
          source, AL_GAIN, (float) (gain * Math.clamp(1 - (now - correctionFadeAt) / 40.0, 0, 1)));
      if (now - correctionFadeAt < 40) return;
      flush();
      if (driftMs > 0) {
        active.close();
        active = decode(entry, target);
        status = "正在重新同步";
        return;
      }
    } else if (correctionFadeAt != 0) {
      correctionFadeAt = 0;
      fadeInAt = 0;
    }
    while (!free.isEmpty()) {
      Pcm p = active.pcm.peek();
      if (p == null) break;
      long desired = target * p.rate() / 1000;
      if (p.frame() + p.frames() < desired - 2 * p.rate() / 100) {
        Pcm discarded = active.pcm.poll();
        if (discarded != null) active.memory.release(discarded.samples().length * 4);
        continue;
      }
      p = active.pcm.poll();
      int skip = (int) Math.clamp(desired - p.frame(), 0, p.frames() - 1);
      if (queued.isEmpty()) {
        playedFrame = p.frame() + skip;
        rate = p.rate();
      } else skip = 0;
      int id = free.removeFirst();
      FloatBuffer data = MemoryUtil.memAllocFloat(p.samples().length - skip * p.channels());
      try {
        data.put(p.samples(), skip * p.channels(), data.capacity()).flip();
        alBufferData(
            id,
            p.channels() == 1
                ? EXTFloat32.AL_FORMAT_MONO_FLOAT32
                : EXTFloat32.AL_FORMAT_STEREO_FLOAT32,
            data,
            p.rate());
      } finally {
        MemoryUtil.memFree(data);
        active.memory.release(p.samples().length * 4);
      }
      alSourceQueueBuffers(source, id);
      queued.add(new Pcm(new float[0], p.rate(), p.channels(), p.frame() + p.frames()));
    }
    if (fadeInAt == 0 && !queued.isEmpty()) fadeInAt = now;
    alSourcef(source, AL_GAIN, (float) (gain * Math.clamp((now - fadeInAt) / 40.0, 0, 1)));
    alSourcef(
        source,
        AL_PITCH,
        Math.abs(driftMs) < 20 ? 1f : (float) Math.clamp(1 - driftMs / 15000, .99, 1.01));
    if (!queued.isEmpty() && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
      alSourcePlay(source);
      if (firstOutputAt == 0) firstOutputAt = dev.shironeko.music.MusicTogether.now();
    }
    buffering = queued.isEmpty() && !active.done;
    status = queued.isEmpty() ? "正在缓冲并追齐" : "";
    if (active.done && active.pcm.isEmpty() && queued.isEmpty()) status = "等待切歌";
  }

  public void clear() {
    PlaybackInput old = input;
    input = new PlaybackInput(null, 0, old.gain(), old.generation() + 1);
  }

  public void close() {
    running = false;
    thread.interrupt();
    if (active != null) active.close();
    if (preload != null) preload.close();
    decoders.shutdownNow();
    if (ownsRepository) repository.close();
    rangeClosers.shutdown();
    if (Thread.currentThread() != thread)
      try {
        thread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
  }
}
