#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/log.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#include <lame/lame.h>

/*
 * libavcodec builds codec-private static tables lazily on the first
 * avcodec_open2() -- for the MP3 decoder that means the Huffman VLC tables in
 * libavcodec/mpegaudiodec_common.c. FFmpeg guards that with ff_thread_once(),
 * which is a real pthread_once() only when FFmpeg is built with threading; in
 * single-threaded builds (see libavutil/thread.h) it degrades to a non-atomic
 * `if (!flag) { init(); flag = 1; }`. Since several conversion workers open the
 * MP3 decoder at the same time, that initializer could run twice, build the
 * same static table twice and abort inside libavcodec/vlc.c
 * ("incorrect codes" / "Assertion ret >= 0 failed").
 *
 * Building those tables once, from a single thread, before any concurrent use
 * keeps the lazy initialization race-free even in single-threaded builds.
 */
static pthread_once_t codec_init_once = PTHREAD_ONCE_INIT;

static void warm_up_codec(const char *name, int is_encoder, int sample_rate, int channels)
{
    const AVCodec *codec = is_encoder
        ? avcodec_find_encoder_by_name(name)
        : avcodec_find_decoder_by_name(name);
    if (!codec) return;

    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) return;

    if (is_encoder) {
        ctx->sample_rate = sample_rate;
        ctx->sample_fmt = (codec->sample_fmts && codec->sample_fmts[0] != AV_SAMPLE_FMT_NONE)
            ? codec->sample_fmts[0]
            : AV_SAMPLE_FMT_S16;
        av_channel_layout_default(&ctx->ch_layout, channels);
        ctx->bit_rate = 32000;
        ctx->time_base = (AVRational){ 1, sample_rate };
    } else {
        /* Decoder tables do not depend on these, but provide sane defaults. */
        ctx->sample_rate = sample_rate > 0 ? sample_rate : 24000;
        av_channel_layout_default(&ctx->ch_layout, 1);
        ctx->thread_count = 1;
        ctx->thread_type = 0;
    }

    avcodec_open2(ctx, codec, NULL);
    avcodec_free_context(&ctx);
}

static void warm_up_codecs(void)
{
    av_log_set_level(AV_LOG_ERROR);
    /* MP3 decoders share the lazily-built Huffman VLC tables. */
    warm_up_codec("mp3", 0, 0, 0);
    warm_up_codec("mp3float", 0, 0, 0);
    /* Encoders are opened from worker threads as well. */
    warm_up_codec("libopus", 1, 24000, 1);
    warm_up_codec("libmp3lame", 1, 24000, 1);
}

static void hedgehog_ensure_codecs_ready(void)
{
    pthread_once(&codec_init_once, warm_up_codecs);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    hedgehog_ensure_codecs_ready();
    return JNI_VERSION_1_6;
}

/*
 * Quick sanity check for MPEG audio payloads (ID3 tag or a frame sync within
 * the first few KiB). Used to reject non-audio data before handing it to
 * libavformat, instead of letting format probing guess a random demuxer.
 */
static int looks_like_mp3(const uint8_t *data, size_t size)
{
    if (!data || size < 4) return 0;
    if (memcmp(data, "ID3", 3) == 0) return 1;
    size_t limit = size < 4096 ? size : 4096;
    for (size_t i = 0; i + 3 < limit; i++) {
        if (data[i] != 0xFF) continue;
        /* Frame sync: 11 bits set, version != reserved, layer != reserved. */
        if ((data[i + 1] & 0xE0) != 0xE0) continue;
        if ((data[i + 1] & 0x18) == 0x08) continue;
        if ((data[i + 1] & 0x06) == 0x00) continue;
        uint8_t bitrate_index = (uint8_t)((data[i + 2] & 0xF0) >> 4);
        uint8_t sample_rate_index = (uint8_t)((data[i + 2] & 0x0C) >> 2);
        if (bitrate_index == 0x0F || sample_rate_index == 0x03) continue;
        return 1;
    }
    return 0;
}

typedef struct {
    const uint8_t *data;
    size_t size;
    size_t pos;
} BufferReader;

static int read_packet(void *opaque, uint8_t *buf, int buf_size) {
    BufferReader *br = (BufferReader *)opaque;
    if (br->pos >= br->size) {
        return AVERROR_EOF;
    }
    size_t rem = br->size - br->pos;
    if ((size_t)buf_size > rem) {
        buf_size = (int)rem;
    }
    memcpy(buf, br->data + br->pos, (size_t)buf_size);
    br->pos += (size_t)buf_size;
    return buf_size;
}

static int64_t seek_packet(void *opaque, int64_t offset, int whence) {
    BufferReader *br = (BufferReader *)opaque;
    if (whence == AVSEEK_SIZE) {
        return (int64_t)br->size;
    }
    int64_t new_pos = 0;
    if (whence == SEEK_SET) {
        new_pos = offset;
    } else if (whence == SEEK_CUR) {
        new_pos = (int64_t)br->pos + offset;
    } else if (whence == SEEK_END) {
        new_pos = (int64_t)br->size + offset;
    } else {
        return -1;
    }
    if (new_pos < 0 || (size_t)new_pos > br->size) {
        return -1;
    }
    br->pos = (size_t)new_pos;
    return new_pos;
}

static uint16_t read_u16_le(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0]) | (((uint16_t)p[1]) << 8));
}

static uint32_t read_u32_le(const uint8_t *p) {
    return ((uint32_t)p[0]) |
           (((uint32_t)p[1]) << 8) |
           (((uint32_t)p[2]) << 16) |
           (((uint32_t)p[3]) << 24);
}

/*
 * Parses an in-memory RIFF WAVE header.
 * Returns 1 if a valid PCM WAVE header was found, 0 otherwise.
 */
static int parse_wav_mem_header(
    const uint8_t *in_data,
    size_t in_size,
    const uint8_t **pcm_ptr_out,
    size_t *pcm_bytes_out,
    int *sample_rate_out,
    int *channels_out,
    int *bits_per_sample_out
) {
    if (!in_data || in_size < 44) return 0;
    if (memcmp(in_data, "RIFF", 4) != 0 || memcmp(in_data + 8, "WAVE", 4) != 0) {
        return 0;
    }

    int audio_fmt = 1;
    int channels = 1;
    int sample_rate = 24000;
    int bits_per_sample = 16;
    size_t pos = 12;
    int found_data = 0;
    const uint8_t *pcm_ptr = in_data + 44;
    size_t pcm_bytes = in_size - 44;

    while (pos + 8 <= in_size) {
        uint32_t chunk_size = read_u32_le(in_data + pos + 4);
        size_t chunk_data_pos = pos + 8;
        if (memcmp(in_data + pos, "fmt ", 4) == 0 && chunk_size >= 16 && chunk_data_pos + 16 <= in_size) {
            audio_fmt = (int)read_u16_le(in_data + chunk_data_pos);
            channels = (int)read_u16_le(in_data + chunk_data_pos + 2);
            uint32_t sr = read_u32_le(in_data + chunk_data_pos + 4);
            if (sr >= 8000 && sr <= 96000) {
                sample_rate = (int)sr;
            }
            bits_per_sample = (int)read_u16_le(in_data + chunk_data_pos + 14);
        } else if (memcmp(in_data + pos, "data", 4) == 0) {
            if (chunk_data_pos <= in_size) {
                size_t avail = in_size - chunk_data_pos;
                pcm_ptr = in_data + chunk_data_pos;
                pcm_bytes = (chunk_size <= avail) ? (size_t)chunk_size : avail;
                found_data = 1;
            }
            break;
        }
        size_t step = (size_t)chunk_size + (chunk_size & 1u);
        if (step > in_size - chunk_data_pos) break;
        pos = chunk_data_pos + step;
    }

    if (audio_fmt != 1) return 0;
    if (!found_data && in_size >= 44) {
        pcm_ptr = in_data + 44;
        pcm_bytes = in_size - 44;
    }

    if (pcm_ptr_out) *pcm_ptr_out = pcm_ptr;
    if (pcm_bytes_out) *pcm_bytes_out = pcm_bytes;
    if (sample_rate_out) *sample_rate_out = sample_rate;
    if (channels_out) *channels_out = channels;
    if (bits_per_sample_out) *bits_per_sample_out = bits_per_sample;
    return 1;
}

/*
 * Parses a RIFF WAVE header from an open FILE*.
 * Returns 1 if a valid PCM WAVE header was found and positions the file at data_offset, 0 otherwise.
 */
static int parse_wav_file_header(
    FILE *fp,
    size_t *data_offset_out,
    size_t *pcm_bytes_out,
    int *sample_rate_out,
    int *channels_out,
    int *bits_per_sample_out
) {
    if (!fp) return 0;
    if (fseek(fp, 0, SEEK_END) != 0) return 0;
    long file_len = ftell(fp);
    if (file_len < 44) return 0;
    if (fseek(fp, 0, SEEK_SET) != 0) return 0;

    uint8_t hdr[256];
    size_t to_read = (size_t)file_len < sizeof(hdr) ? (size_t)file_len : sizeof(hdr);
    size_t nread = fread(hdr, 1, to_read, fp);
    if (nread < 44) return 0;
    if (memcmp(hdr, "RIFF", 4) != 0 || memcmp(hdr + 8, "WAVE", 4) != 0) {
        return 0;
    }

    int audio_fmt = 1;
    int channels = 1;
    int sample_rate = 24000;
    int bits_per_sample = 16;
    size_t pos = 12;
    size_t data_offset = 44;
    size_t pcm_bytes = (size_t)file_len - 44;

    while (pos + 8 <= nread) {
        uint32_t chunk_size = read_u32_le(hdr + pos + 4);
        size_t chunk_data_pos = pos + 8;
        if (memcmp(hdr + pos, "fmt ", 4) == 0 && chunk_size >= 16 && chunk_data_pos + 16 <= nread) {
            audio_fmt = (int)read_u16_le(hdr + chunk_data_pos);
            channels = (int)read_u16_le(hdr + chunk_data_pos + 2);
            uint32_t sr = read_u32_le(hdr + chunk_data_pos + 4);
            if (sr >= 8000 && sr <= 96000) {
                sample_rate = (int)sr;
            }
            bits_per_sample = (int)read_u16_le(hdr + chunk_data_pos + 14);
        } else if (memcmp(hdr + pos, "data", 4) == 0) {
            data_offset = chunk_data_pos;
            if (data_offset <= (size_t)file_len) {
                size_t avail = (size_t)file_len - data_offset;
                pcm_bytes = (chunk_size <= avail) ? (size_t)chunk_size : avail;
            } else {
                pcm_bytes = 0;
            }
            break;
        }
        size_t step = (size_t)chunk_size + (chunk_size & 1u);
        if (step > nread - chunk_data_pos) break;
        pos = chunk_data_pos + step;
    }

    if (audio_fmt != 1) return 0;
    if (fseek(fp, (long)data_offset, SEEK_SET) != 0) return 0;

    if (data_offset_out) *data_offset_out = data_offset;
    if (pcm_bytes_out) *pcm_bytes_out = pcm_bytes;
    if (sample_rate_out) *sample_rate_out = sample_rate;
    if (channels_out) *channels_out = channels;
    if (bits_per_sample_out) *bits_per_sample_out = bits_per_sample;
    return 1;
}

static int init_opus_output_encoder(
    AVFormatContext **out_fmt_ctx_ptr,
    AVCodecContext **enc_ctx_ptr,
    AVStream **out_stream_ptr,
    AVFrame **enc_frame_ptr,
    int *frame_size_out
) {
    AVFormatContext *out_fmt_ctx = NULL;
    AVCodecContext *enc_ctx = NULL;
    AVStream *out_stream = NULL;
    AVFrame *enc_frame = NULL;

    if (avformat_alloc_output_context2(&out_fmt_ctx, NULL, "opus", NULL) < 0 || !out_fmt_ctx) {
        goto fail;
    }
    if (avio_open_dyn_buf(&out_fmt_ctx->pb) < 0) goto fail;

    const AVCodec *encoder = avcodec_find_encoder_by_name("libopus");
    if (!encoder) encoder = avcodec_find_encoder(AV_CODEC_ID_OPUS);
    if (!encoder) goto fail;

    out_stream = avformat_new_stream(out_fmt_ctx, NULL);
    if (!out_stream) goto fail;

    enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) goto fail;

    enc_ctx->sample_rate = 24000;
    av_channel_layout_default(&enc_ctx->ch_layout, 1); /* -ac 1 */
    enc_ctx->sample_fmt = AV_SAMPLE_FMT_S16;
    enc_ctx->bit_rate = 32000; /* -b:a 32k */
    enc_ctx->time_base = (AVRational){ 1, enc_ctx->sample_rate };

    /* Exact FFmpeg libopus private options: -application voip -frame_duration 60 -vbr on */
    av_opt_set(enc_ctx->priv_data, "application", "voip", 0);
    av_opt_set(enc_ctx->priv_data, "frame_duration", "60", 0);
    av_opt_set(enc_ctx->priv_data, "vbr", "on", 0);

    if (out_fmt_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        enc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if (avcodec_open2(enc_ctx, encoder, NULL) < 0) goto fail;
    if (avcodec_parameters_from_context(out_stream->codecpar, enc_ctx) < 0) goto fail;
    out_stream->time_base = enc_ctx->time_base;

    if (avformat_write_header(out_fmt_ctx, NULL) < 0) goto fail;

    int frame_size = enc_ctx->frame_size > 0 ? enc_ctx->frame_size : 1440; /* 60ms @ 24kHz = 1440 */
    enc_frame = av_frame_alloc();
    if (!enc_frame) goto fail;
    enc_frame->nb_samples = frame_size;
    av_channel_layout_copy(&enc_frame->ch_layout, &enc_ctx->ch_layout);
    enc_frame->format = enc_ctx->sample_fmt;
    enc_frame->sample_rate = enc_ctx->sample_rate;
    if (av_frame_get_buffer(enc_frame, 0) < 0) goto fail;

    *out_fmt_ctx_ptr = out_fmt_ctx;
    *enc_ctx_ptr = enc_ctx;
    *out_stream_ptr = out_stream;
    *enc_frame_ptr = enc_frame;
    *frame_size_out = frame_size;
    return 0;

fail:
    if (enc_frame) av_frame_free(&enc_frame);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (out_fmt_ctx) {
        if (out_fmt_ctx->pb) {
            uint8_t *dummy = NULL;
            avio_close_dyn_buf(out_fmt_ctx->pb, &dummy);
            out_fmt_ctx->pb = NULL;
            if (dummy) av_free(dummy);
        }
        avformat_free_context(out_fmt_ctx);
    }
    return -1;
}

static int drain_opus_fifo(
    AVAudioFifo *fifo,
    int flush_all,
    int frame_size,
    AVFrame *enc_frame,
    AVCodecContext *enc_ctx,
    AVFormatContext *out_fmt_ctx,
    AVStream *out_stream,
    AVPacket *out_pkt,
    int64_t *next_pts
) {
    while (av_audio_fifo_size(fifo) >= frame_size || (flush_all && av_audio_fifo_size(fifo) > 0)) {
        int cur_size = av_audio_fifo_size(fifo);
        if (cur_size > frame_size) cur_size = frame_size;
        if (av_frame_make_writable(enc_frame) < 0) return -1;
        enc_frame->nb_samples = frame_size;
        if (cur_size < frame_size) {
            av_samples_set_silence(
                enc_frame->data,
                cur_size,
                frame_size - cur_size,
                enc_ctx->ch_layout.nb_channels,
                enc_ctx->sample_fmt
            );
        }
        if (av_audio_fifo_read(fifo, (void **)enc_frame->data, cur_size) < cur_size) return -1;
        enc_frame->pts = *next_pts;
        *next_pts += frame_size;
        if (avcodec_send_frame(enc_ctx, enc_frame) < 0) return -1;
        while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
            av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
            out_pkt->stream_index = out_stream->index;
            if (av_interleaved_write_frame(out_fmt_ctx, out_pkt) < 0) return -1;
            av_packet_unref(out_pkt);
        }
    }
    return 0;
}

/*
 * Fast-path: encodes raw 24 kHz 16-bit mono PCM directly into Ogg Opus without
 * demuxer, decoder, or FIFO intermediate copies.
 */
static int hedgehog_pcm24k_mono_to_opus_mem(
    const uint8_t *pcm_ptr,
    size_t pcm_bytes,
    uint8_t **out_data,
    int *out_size
) {
    *out_data = NULL;
    *out_size = 0;
    hedgehog_ensure_codecs_ready();
    size_t total_samples = pcm_bytes / 2;
    if (!pcm_ptr || total_samples == 0) return -1;
    av_log_set_level(AV_LOG_ERROR);

    int ret = -1;
    AVFormatContext *out_fmt_ctx = NULL;
    AVCodecContext *enc_ctx = NULL;
    AVStream *out_stream = NULL;
    AVFrame *enc_frame = NULL;
    AVPacket *out_pkt = NULL;
    int frame_size = 1440;
    int64_t next_pts = 0;

    if (init_opus_output_encoder(&out_fmt_ctx, &enc_ctx, &out_stream, &enc_frame, &frame_size) < 0) {
        goto cleanup;
    }

    out_pkt = av_packet_alloc();
    if (!out_pkt) goto cleanup;

    size_t sample_offset = 0;
    while (sample_offset < total_samples) {
        size_t rem = total_samples - sample_offset;
        int cur_samples = (rem > (size_t)frame_size) ? frame_size : (int)rem;
        if (av_frame_make_writable(enc_frame) < 0) goto cleanup;
        enc_frame->nb_samples = frame_size;
        memcpy(enc_frame->data[0], pcm_ptr + sample_offset * 2, (size_t)cur_samples * 2);
        if (cur_samples < frame_size) {
            av_samples_set_silence(
                enc_frame->data,
                cur_samples,
                frame_size - cur_samples,
                enc_ctx->ch_layout.nb_channels,
                enc_ctx->sample_fmt
            );
        }
        enc_frame->pts = next_pts;
        next_pts += frame_size;
        sample_offset += (size_t)cur_samples;

        if (avcodec_send_frame(enc_ctx, enc_frame) < 0) goto cleanup;
        while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
            av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
            out_pkt->stream_index = out_stream->index;
            if (av_interleaved_write_frame(out_fmt_ctx, out_pkt) < 0) goto cleanup;
            av_packet_unref(out_pkt);
        }
    }

    avcodec_send_frame(enc_ctx, NULL);
    while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
        av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
        out_pkt->stream_index = out_stream->index;
        if (av_interleaved_write_frame(out_fmt_ctx, out_pkt) < 0) goto cleanup;
        av_packet_unref(out_pkt);
    }

    if (av_write_trailer(out_fmt_ctx) < 0) goto cleanup;
    ret = 0;

cleanup:
    if (out_fmt_ctx && out_fmt_ctx->pb) {
        uint8_t *dyn_buf = NULL;
        int dyn_size = avio_close_dyn_buf(out_fmt_ctx->pb, &dyn_buf);
        out_fmt_ctx->pb = NULL;
        if (ret == 0 && dyn_buf && dyn_size > 0) {
            *out_data = dyn_buf;
            *out_size = dyn_size;
        } else if (dyn_buf) {
            av_free(dyn_buf);
        }
    }
    if (out_pkt) av_packet_free(&out_pkt);
    if (enc_frame) av_frame_free(&enc_frame);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (out_fmt_ctx) avformat_free_context(out_fmt_ctx);
    return ret;
}

typedef struct {
    SwrContext *swr_ctx;
    int src_rate;
    int src_fmt;
    int src_ch;
    uint8_t **swr_buf;
    int swr_buf_cap;
} ReusableResampler;

static void free_reusable_resampler(ReusableResampler *rr) {
    if (!rr) return;
    if (rr->swr_buf) {
        if (rr->swr_buf[0]) av_freep(&rr->swr_buf[0]);
        av_freep(&rr->swr_buf);
    }
    if (rr->swr_ctx) {
        swr_free(&rr->swr_ctx);
    }
    memset(rr, 0, sizeof(*rr));
}

static int ensure_reusable_resampler(
    ReusableResampler *rr,
    const AVCodecContext *dec_ctx,
    const AVCodecContext *enc_ctx
) {
    int ch = dec_ctx->ch_layout.nb_channels > 0 ? dec_ctx->ch_layout.nb_channels : 1;
    if (rr->swr_ctx &&
        rr->src_rate == dec_ctx->sample_rate &&
        rr->src_fmt == (int)dec_ctx->sample_fmt &&
        rr->src_ch == ch) {
        return 0;
    }
    if (rr->swr_ctx) {
        swr_free(&rr->swr_ctx);
    }
    if (swr_alloc_set_opts2(
            &rr->swr_ctx,
            &enc_ctx->ch_layout,
            enc_ctx->sample_fmt,
            enc_ctx->sample_rate,
            &dec_ctx->ch_layout,
            dec_ctx->sample_fmt,
            dec_ctx->sample_rate,
            0,
            NULL) < 0) {
        return -1;
    }
    if (swr_init(rr->swr_ctx) < 0) {
        swr_free(&rr->swr_ctx);
        return -1;
    }
    rr->src_rate = dec_ctx->sample_rate;
    rr->src_fmt = (int)dec_ctx->sample_fmt;
    rr->src_ch = ch;
    return 0;
}

static int ensure_swr_buf_capacity(
    ReusableResampler *rr,
    const AVCodecContext *enc_ctx,
    int needed_samples
) {
    if (needed_samples <= rr->swr_buf_cap && rr->swr_buf && rr->swr_buf[0]) {
        return 0;
    }
    if (rr->swr_buf) {
        if (rr->swr_buf[0]) av_freep(&rr->swr_buf[0]);
        av_freep(&rr->swr_buf);
        rr->swr_buf_cap = 0;
    }
    int alloc_samples = needed_samples < 4096 ? 4096 : (needed_samples + 1024);
    if (av_samples_alloc_array_and_samples(
            &rr->swr_buf,
            NULL,
            enc_ctx->ch_layout.nb_channels,
            alloc_samples,
            enc_ctx->sample_fmt,
            0) < 0) {
        rr->swr_buf = NULL;
        rr->swr_buf_cap = 0;
        return -1;
    }
    rr->swr_buf_cap = alloc_samples;
    return 0;
}

static int decode_fmt_ctx_into_opus_fifo(
    AVFormatContext *in_fmt_ctx,
    AVCodecContext *enc_ctx,
    AVFormatContext *out_fmt_ctx,
    AVStream *out_stream,
    AVAudioFifo *fifo,
    ReusableResampler *rr,
    AVPacket *pkt,
    AVPacket *out_pkt,
    AVFrame *in_frame,
    AVFrame *enc_frame,
    int frame_size,
    int64_t *next_pts
) {
    if (avformat_find_stream_info(in_fmt_ctx, NULL) < 0) return -1;

    int audio_stream_idx = -1;
    for (unsigned int i = 0; i < in_fmt_ctx->nb_streams; i++) {
        if (in_fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audio_stream_idx = (int)i;
            break;
        }
    }
    if (audio_stream_idx < 0) return -1;

    AVCodecParameters *in_codecpar = in_fmt_ctx->streams[audio_stream_idx]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(in_codecpar->codec_id);
    if (!decoder) return -1;

    AVCodecContext *dec_ctx = avcodec_alloc_context3(decoder);
    if (!dec_ctx) return -1;

    int ret = -1;
    if (avcodec_parameters_to_context(dec_ctx, in_codecpar) < 0) goto done;
    /* Single-threaded decoding: chapters are already assembled in parallel. */
    dec_ctx->thread_count = 1;
    dec_ctx->thread_type = 0;
    if (avcodec_open2(dec_ctx, decoder, NULL) < 0) goto done;

    if (dec_ctx->ch_layout.nb_channels <= 0) {
        av_channel_layout_default(&dec_ctx->ch_layout, 1);
    }

    int passthrough_pcm = (
        dec_ctx->sample_rate == enc_ctx->sample_rate &&
        dec_ctx->sample_fmt == enc_ctx->sample_fmt &&
        dec_ctx->ch_layout.nb_channels == enc_ctx->ch_layout.nb_channels
    );

    if (!passthrough_pcm) {
        if (ensure_reusable_resampler(rr, dec_ctx, enc_ctx) < 0) goto done;
    }

    while (av_read_frame(in_fmt_ctx, pkt) >= 0) {
        if (pkt->stream_index == audio_stream_idx) {
            if (avcodec_send_packet(dec_ctx, pkt) >= 0) {
                while (avcodec_receive_frame(dec_ctx, in_frame) == 0) {
                    if (passthrough_pcm) {
                        if (in_frame->nb_samples > 0) {
                            av_audio_fifo_write(fifo, (void **)in_frame->extended_data, in_frame->nb_samples);
                        }
                    } else {
                        int max_out = swr_get_out_samples(rr->swr_ctx, in_frame->nb_samples);
                        if (max_out > 0 && ensure_swr_buf_capacity(rr, enc_ctx, max_out) == 0) {
                            int conv_samples = swr_convert(
                                rr->swr_ctx,
                                rr->swr_buf,
                                max_out,
                                (const uint8_t **)in_frame->extended_data,
                                in_frame->nb_samples
                            );
                            if (conv_samples > 0) {
                                av_audio_fifo_write(fifo, (void **)rr->swr_buf, conv_samples);
                            }
                        }
                    }
                    av_frame_unref(in_frame);
                    if (drain_opus_fifo(fifo, 0, frame_size, enc_frame, enc_ctx, out_fmt_ctx, out_stream, out_pkt, next_pts) < 0) {
                        av_packet_unref(pkt);
                        goto done;
                    }
                }
            }
        }
        av_packet_unref(pkt);
    }

    avcodec_send_packet(dec_ctx, NULL);
    while (avcodec_receive_frame(dec_ctx, in_frame) == 0) {
        if (passthrough_pcm) {
            if (in_frame->nb_samples > 0) {
                av_audio_fifo_write(fifo, (void **)in_frame->extended_data, in_frame->nb_samples);
            }
        } else {
            int max_out = swr_get_out_samples(rr->swr_ctx, in_frame->nb_samples);
            if (max_out > 0 && ensure_swr_buf_capacity(rr, enc_ctx, max_out) == 0) {
                int conv_samples = swr_convert(
                    rr->swr_ctx,
                    rr->swr_buf,
                    max_out,
                    (const uint8_t **)in_frame->extended_data,
                    in_frame->nb_samples
                );
                if (conv_samples > 0) {
                    av_audio_fifo_write(fifo, (void **)rr->swr_buf, conv_samples);
                }
            }
        }
        av_frame_unref(in_frame);
    }

    if (!passthrough_pcm && rr->swr_ctx) {
        int delayed = swr_get_out_samples(rr->swr_ctx, 0);
        if (delayed > 0 && ensure_swr_buf_capacity(rr, enc_ctx, delayed) == 0) {
            int conv_samples = swr_convert(rr->swr_ctx, rr->swr_buf, delayed, NULL, 0);
            if (conv_samples > 0) {
                av_audio_fifo_write(fifo, (void **)rr->swr_buf, conv_samples);
            }
        }
    }

    if (drain_opus_fifo(fifo, 0, frame_size, enc_frame, enc_ctx, out_fmt_ctx, out_stream, out_pkt, next_pts) < 0) {
        goto done;
    }

    ret = 0;

done:
    avcodec_free_context(&dec_ctx);
    return ret;
}

static int decode_mem_buffer_into_opus_fifo(
    const uint8_t *in_data,
    size_t in_size,
    const AVInputFormat *demuxer,
    AVCodecContext *enc_ctx,
    AVFormatContext *out_fmt_ctx,
    AVStream *out_stream,
    AVAudioFifo *fifo,
    ReusableResampler *rr,
    AVPacket *pkt,
    AVPacket *out_pkt,
    AVFrame *in_frame,
    AVFrame *enc_frame,
    int frame_size,
    int64_t *next_pts
) {
    if (!in_data || in_size == 0) return 0;

    const uint8_t *pcm_ptr = NULL;
    size_t pcm_bytes = 0;
    int wav_rate = 0, wav_ch = 0, wav_bits = 0;
    if (parse_wav_mem_header(in_data, in_size, &pcm_ptr, &pcm_bytes, &wav_rate, &wav_ch, &wav_bits) &&
        wav_rate == 24000 && wav_ch == 1 && wav_bits == 16) {
        int samples = (int)(pcm_bytes / 2);
        if (samples > 0) {
            void *plane = (void *)pcm_ptr;
            if (av_audio_fifo_write(fifo, &plane, samples) < samples) return -1;
            if (drain_opus_fifo(fifo, 0, frame_size, enc_frame, enc_ctx, out_fmt_ctx, out_stream, out_pkt, next_pts) < 0) {
                return -1;
            }
        }
        return 0;
    }

    BufferReader br = { in_data, in_size, 0 };
    uint8_t *in_avio_buf = (uint8_t *)av_malloc(32768);
    if (!in_avio_buf) return -1;

    AVIOContext *in_avio_ctx = avio_alloc_context(in_avio_buf, 32768, 0, &br, read_packet, NULL, seek_packet);
    if (!in_avio_ctx) {
        av_free(in_avio_buf);
        return -1;
    }

    AVFormatContext *in_fmt_ctx = avformat_alloc_context();
    if (!in_fmt_ctx) {
        av_freep(&in_avio_ctx->buffer);
        avio_context_free(&in_avio_ctx);
        return -1;
    }
    in_fmt_ctx->pb = in_avio_ctx;
    in_fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;
    in_fmt_ctx->probesize = 8192;
    in_fmt_ctx->max_analyze_duration = 0;

    int ret = -1;
    /*
     * Pin the demuxer instead of letting format probing pick one: with the
     * deliberately tiny probe size, stray bytes could otherwise be detected as
     * another container and fail in unrelated codec initialization code.
     */
    if (demuxer && avformat_open_input(&in_fmt_ctx, NULL, demuxer, NULL) >= 0) {
        ret = decode_fmt_ctx_into_opus_fifo(
            in_fmt_ctx,
            enc_ctx,
            out_fmt_ctx,
            out_stream,
            fifo,
            rr,
            pkt,
            out_pkt,
            in_frame,
            enc_frame,
            frame_size,
            next_pts
        );
    }

    if (in_fmt_ctx) avformat_close_input(&in_fmt_ctx);
    if (in_avio_ctx) {
        av_freep(&in_avio_ctx->buffer);
        avio_context_free(&in_avio_ctx);
    }
    return ret;
}

/*
 * Transcodes an in-memory MP3 or WAV buffer to an in-memory .opus (Ogg Opus) buffer
 * using exact FFmpeg settings:
 *   -c:a libopus -b:a 32k -ac 1 -application voip -frame_duration 60 -vbr on
 */
int hedgehog_mp3_to_opus_mem(
    const uint8_t *in_data,
    size_t in_size,
    uint8_t **out_data,
    int *out_size
) {
    *out_data = NULL;
    *out_size = 0;
    if (!in_data || in_size == 0) return -1;

    hedgehog_ensure_codecs_ready();

    const uint8_t *pcm_ptr = NULL;
    size_t pcm_bytes = 0;
    int wav_rate = 0, wav_ch = 0, wav_bits = 0;
    int has_wav_header = parse_wav_mem_header(
        in_data, in_size, &pcm_ptr, &pcm_bytes, &wav_rate, &wav_ch, &wav_bits);
    if (has_wav_header &&
        wav_rate == 24000 && wav_ch == 1 && wav_bits == 16 && pcm_bytes >= 2) {
        return hedgehog_pcm24k_mono_to_opus_mem(pcm_ptr, pcm_bytes, out_data, out_size);
    }

    int is_mp3 = !has_wav_header && looks_like_mp3(in_data, in_size);
    if (!has_wav_header && !is_mp3) {
        av_log(NULL, AV_LOG_ERROR,
               "hedgehog: input is neither RIFF/WAVE nor MPEG audio, refusing to transcode\n");
        return -1;
    }
    const AVInputFormat *demuxer = av_find_input_format(is_mp3 ? "mp3" : "wav");
    if (!demuxer) return -1;

    av_log_set_level(AV_LOG_ERROR);

    int ret = -1;
    AVFormatContext *out_fmt_ctx = NULL;
    AVCodecContext *enc_ctx = NULL;
    AVStream *out_stream = NULL;
    AVFrame *enc_frame = NULL;
    AVAudioFifo *fifo = NULL;
    AVPacket *pkt = NULL;
    AVPacket *out_pkt = NULL;
    AVFrame *in_frame = NULL;
    ReusableResampler rr;
    memset(&rr, 0, sizeof(rr));
    int frame_size = 1440;
    int64_t next_pts = 0;

    if (init_opus_output_encoder(&out_fmt_ctx, &enc_ctx, &out_stream, &enc_frame, &frame_size) < 0) {
        goto cleanup;
    }

    fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, frame_size * 4);
    pkt = av_packet_alloc();
    out_pkt = av_packet_alloc();
    in_frame = av_frame_alloc();
    if (!fifo || !pkt || !out_pkt || !in_frame) goto cleanup;

    if (decode_mem_buffer_into_opus_fifo(
            in_data,
            in_size,
            demuxer,
            enc_ctx,
            out_fmt_ctx,
            out_stream,
            fifo,
            &rr,
            pkt,
            out_pkt,
            in_frame,
            enc_frame,
            frame_size,
            &next_pts) < 0) {
        goto cleanup;
    }

    if (drain_opus_fifo(fifo, 1, frame_size, enc_frame, enc_ctx, out_fmt_ctx, out_stream, out_pkt, &next_pts) < 0) {
        goto cleanup;
    }

    avcodec_send_frame(enc_ctx, NULL);
    while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) {
        av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base);
        out_pkt->stream_index = out_stream->index;
        if (av_interleaved_write_frame(out_fmt_ctx, out_pkt) < 0) goto cleanup;
        av_packet_unref(out_pkt);
    }

    if (av_write_trailer(out_fmt_ctx) < 0) goto cleanup;
    ret = 0;

cleanup:
    if (out_fmt_ctx && out_fmt_ctx->pb) {
        uint8_t *dyn_buf = NULL;
        int dyn_size = avio_close_dyn_buf(out_fmt_ctx->pb, &dyn_buf);
        out_fmt_ctx->pb = NULL;
        if (ret == 0 && dyn_buf && dyn_size > 0) {
            *out_data = dyn_buf;
            *out_size = dyn_size;
        } else if (dyn_buf) {
            av_free(dyn_buf);
        }
    }
    free_reusable_resampler(&rr);
    if (enc_frame) av_frame_free(&enc_frame);
    if (in_frame) av_frame_free(&in_frame);
    if (out_pkt) av_packet_free(&out_pkt);
    if (pkt) av_packet_free(&pkt);
    if (fifo) av_audio_fifo_free(fifo);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (out_fmt_ctx) avformat_free_context(out_fmt_ctx);
    return ret;
}

/*
 * Remuxes/concatenates multiple .opus files into a single continuous .opus file
 * without re-encoding (stream copy with monotonic PTS/DTS).
 */
int hedgehog_merge_opus_files(int num_files, const char **input_paths, const char *output_path) {
    if (num_files <= 0 || !input_paths || !output_path) return -1;
    hedgehog_ensure_codecs_ready();
    av_log_set_level(AV_LOG_ERROR);

    int ret = -1;
    AVFormatContext *out_fmt_ctx = NULL;
    AVStream *out_stream = NULL;
    AVPacket *pkt = NULL;
    int header_written = 0;
    int64_t ts_offset = 0;

    if (avformat_alloc_output_context2(&out_fmt_ctx, NULL, "opus", output_path) < 0 || !out_fmt_ctx) {
        goto cleanup;
    }

    pkt = av_packet_alloc();
    if (!pkt) goto cleanup;

    /* Part files are always Ogg Opus: pin the demuxer instead of probing. */
    const AVInputFormat *ogg_demuxer = av_find_input_format("ogg");

    for (int i = 0; i < num_files; i++) {
        AVFormatContext *in_fmt_ctx = NULL;
        if (avformat_open_input(&in_fmt_ctx, input_paths[i], ogg_demuxer, NULL) < 0) {
            continue;
        }
        /* Part files are small standalone Ogg Opus streams: avoid scanning extra pages. */
        in_fmt_ctx->probesize = 8192;
        in_fmt_ctx->max_analyze_duration = 0;
        if (avformat_find_stream_info(in_fmt_ctx, NULL) < 0) {
            avformat_close_input(&in_fmt_ctx);
            continue;
        }
        int audio_idx = -1;
        for (unsigned int s = 0; s < in_fmt_ctx->nb_streams; s++) {
            if (in_fmt_ctx->streams[s]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
                audio_idx = (int)s;
                break;
            }
        }
        if (audio_idx < 0) {
            avformat_close_input(&in_fmt_ctx);
            continue;
        }
        AVStream *in_stream = in_fmt_ctx->streams[audio_idx];

        if (!header_written) {
            out_stream = avformat_new_stream(out_fmt_ctx, NULL);
            if (!out_stream) {
                avformat_close_input(&in_fmt_ctx);
                goto cleanup;
            }
            if (avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar) < 0) {
                avformat_close_input(&in_fmt_ctx);
                goto cleanup;
            }
            out_stream->codecpar->codec_tag = 0;
            out_stream->time_base = in_stream->time_base;
            if (!(out_fmt_ctx->oformat->flags & AVFMT_NOFILE)) {
                if (avio_open(&out_fmt_ctx->pb, output_path, AVIO_FLAG_WRITE) < 0) {
                    avformat_close_input(&in_fmt_ctx);
                    goto cleanup;
                }
            }
            if (avformat_write_header(out_fmt_ctx, NULL) < 0) {
                avformat_close_input(&in_fmt_ctx);
                goto cleanup;
            }
            header_written = 1;
        }

        int64_t max_end_pts = ts_offset;
        while (av_read_frame(in_fmt_ctx, pkt) >= 0) {
            if (pkt->stream_index == audio_idx) {
                av_packet_rescale_ts(pkt, in_stream->time_base, out_stream->time_base);
                int64_t pts = (pkt->pts != AV_NOPTS_VALUE) ? pkt->pts : 0;
                int64_t dts = (pkt->dts != AV_NOPTS_VALUE) ? pkt->dts : pts;
                int64_t dur = (pkt->duration > 0) ? pkt->duration : 2880;
                if (pts < 0) pts = 0;
                if (dts < 0) dts = 0;
                pkt->pts = pts + ts_offset;
                pkt->dts = dts + ts_offset;
                pkt->stream_index = out_stream->index;
                pkt->pos = -1;
                if (pkt->pts + dur > max_end_pts) {
                    max_end_pts = pkt->pts + dur;
                }
                av_interleaved_write_frame(out_fmt_ctx, pkt);
            }
            av_packet_unref(pkt);
        }

        ts_offset = max_end_pts;
        avformat_close_input(&in_fmt_ctx);
    }

    if (header_written) {
        if (av_write_trailer(out_fmt_ctx) == 0) {
            ret = 0;
        }
    }

cleanup:
    if (pkt) av_packet_free(&pkt);
    if (out_fmt_ctx) {
        if (!(out_fmt_ctx->oformat->flags & AVFMT_NOFILE) && out_fmt_ctx->pb) {
            avio_closep(&out_fmt_ctx->pb);
        }
        avformat_free_context(out_fmt_ctx);
    }
    return ret;
}

/*
 * Encodes an in-memory 16-bit mono WAV (or raw 24kHz s16le PCM) buffer to an in-memory CBR MP3 buffer
 * using libmp3lame (without Xing/VBR header so frame concatenation stays seamless).
 */
int hedgehog_wav_to_mp3_mem(
    const uint8_t *in_data,
    size_t in_size,
    int kbps,
    uint8_t **out_data,
    int *out_size
) {
    *out_data = NULL;
    *out_size = 0;
    if (!in_data || in_size < 2) return -1;
    hedgehog_ensure_codecs_ready();

    int sample_rate = 24000;
    const uint8_t *pcm_ptr = in_data;
    size_t pcm_bytes = in_size;
    int wav_ch = 1, wav_bits = 16;

    parse_wav_mem_header(in_data, in_size, &pcm_ptr, &pcm_bytes, &sample_rate, &wav_ch, &wav_bits);

    int num_samples = (int)(pcm_bytes / 2);
    if (num_samples <= 0) return -1;

    int target_kbps = (kbps == 48) ? 48 : 96;
    lame_global_flags *gfp = lame_init();
    if (!gfp) return -1;

    lame_set_num_channels(gfp, 1);
    lame_set_mode(gfp, MONO);
    lame_set_in_samplerate(gfp, sample_rate);
    lame_set_out_samplerate(gfp, sample_rate);
    lame_set_brate(gfp, target_kbps);
    lame_set_VBR(gfp, vbr_off);
    lame_set_quality(gfp, 5);
    lame_set_bWriteVbrTag(gfp, 0);

    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return -1;
    }

    int mp3_cap = num_samples * 5 / 4 + 7200;
    uint8_t *mp3_buf = (uint8_t *)av_malloc((size_t)mp3_cap);
    if (!mp3_buf) {
        lame_close(gfp);
        return -1;
    }

    int enc_bytes = lame_encode_buffer(
        gfp,
        (const short int *)pcm_ptr,
        NULL,
        num_samples,
        mp3_buf,
        mp3_cap
    );
    if (enc_bytes < 0) {
        av_free(mp3_buf);
        lame_close(gfp);
        return -1;
    }

    int flush_bytes = lame_encode_flush(gfp, mp3_buf + enc_bytes, mp3_cap - enc_bytes);
    if (flush_bytes > 0) {
        enc_bytes += flush_bytes;
    }

    lame_close(gfp);

    if (enc_bytes <= 0) {
        av_free(mp3_buf);
        return -1;
    }

    *out_data = mp3_buf;
    *out_size = enc_bytes;
    return 0;
}

/*
 * Streams an array of 16-bit mono .wav part files directly from disk through libmp3lame
 * into a single MP3 buffer (optionally prepending an intro MP3 buffer), avoiding
 * allocating a merged chapter WAV ByteArray on the JVM heap.
 */
int hedgehog_wav_files_to_mp3(
    int num_files,
    const char **input_paths,
    const uint8_t *intro_mp3,
    size_t intro_size,
    int kbps,
    uint8_t **out_data,
    int *out_size
) {
    *out_data = NULL;
    *out_size = 0;
    if (num_files <= 0 || !input_paths) return -1;
    hedgehog_ensure_codecs_ready();

    size_t total_pcm_bytes = 0;
    int sample_rate = 24000;
    int found_any = 0;

    for (int i = 0; i < num_files; i++) {
        if (!input_paths[i] || !input_paths[i][0]) continue;
        FILE *fp = fopen(input_paths[i], "rb");
        if (!fp) continue;
        size_t data_off = 0, pcm_bytes = 0;
        int sr = 24000, ch = 1, bits = 16;
        if (parse_wav_file_header(fp, &data_off, &pcm_bytes, &sr, &ch, &bits) && pcm_bytes >= 2) {
            if (!found_any) {
                sample_rate = sr;
                found_any = 1;
            }
            total_pcm_bytes += (pcm_bytes & ~(size_t)1u);
        }
        fclose(fp);
    }

    size_t total_samples = total_pcm_bytes / 2;
    if (!found_any || total_samples == 0) return -1;

    int target_kbps = (kbps == 48) ? 48 : 96;
    lame_global_flags *gfp = lame_init();
    if (!gfp) return -1;

    lame_set_num_channels(gfp, 1);
    lame_set_mode(gfp, MONO);
    lame_set_in_samplerate(gfp, sample_rate);
    lame_set_out_samplerate(gfp, sample_rate);
    lame_set_brate(gfp, target_kbps);
    lame_set_VBR(gfp, vbr_off);
    lame_set_quality(gfp, 5);
    lame_set_bWriteVbrTag(gfp, 0);

    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return -1;
    }

    size_t mp3_cap = intro_size + (total_samples * 5 / 4) + 7200;
    uint8_t *mp3_buf = (uint8_t *)av_malloc(mp3_cap);
    uint8_t *pcm_chunk = (uint8_t *)av_malloc(65536);
    if (!mp3_buf || !pcm_chunk) {
        if (mp3_buf) av_free(mp3_buf);
        if (pcm_chunk) av_free(pcm_chunk);
        lame_close(gfp);
        return -1;
    }

    size_t out_pos = 0;
    if (intro_mp3 && intro_size > 0) {
        memcpy(mp3_buf, intro_mp3, intro_size);
        out_pos = intro_size;
    }

    for (int i = 0; i < num_files; i++) {
        if (!input_paths[i] || !input_paths[i][0]) continue;
        FILE *fp = fopen(input_paths[i], "rb");
        if (!fp) continue;
        size_t data_off = 0, pcm_bytes = 0;
        int sr = 24000, ch = 1, bits = 16;
        if (parse_wav_file_header(fp, &data_off, &pcm_bytes, &sr, &ch, &bits) && pcm_bytes >= 2) {
            size_t rem = pcm_bytes & ~(size_t)1u;
            while (rem > 0) {
                size_t to_read = rem > 65536 ? 65536 : rem;
                size_t nread = fread(pcm_chunk, 1, to_read, fp);
                int samples = (int)(nread / 2);
                if (samples <= 0) break;
                int enc_bytes = lame_encode_buffer(
                    gfp,
                    (const short int *)pcm_chunk,
                    NULL,
                    samples,
                    mp3_buf + out_pos,
                    (int)(mp3_cap - out_pos)
                );
                if (enc_bytes < 0) {
                    fclose(fp);
                    av_free(pcm_chunk);
                    av_free(mp3_buf);
                    lame_close(gfp);
                    return -1;
                }
                out_pos += (size_t)enc_bytes;
                rem -= nread;
                if (nread < to_read) break;
            }
        }
        fclose(fp);
    }

    int flush_bytes = lame_encode_flush(gfp, mp3_buf + out_pos, (int)(mp3_cap - out_pos));
    if (flush_bytes > 0) {
        out_pos += (size_t)flush_bytes;
    }

    av_free(pcm_chunk);
    lame_close(gfp);

    if (out_pos <= intro_size) {
        av_free(mp3_buf);
        return -1;
    }

    *out_data = mp3_buf;
    *out_size = (int)out_pos;
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_dm1sh_hedgehog_tts_OpusConverter_nativeConvertMp3ToOpus(
    JNIEnv *env,
    jclass clazz,
    jbyteArray mp3_bytes
) {
    (void)clazz;
    if (!mp3_bytes) return NULL;
    jsize len = (*env)->GetArrayLength(env, mp3_bytes);
    if (len <= 0) return NULL;

    jbyte *in_ptr = (*env)->GetByteArrayElements(env, mp3_bytes, NULL);
    if (!in_ptr) return NULL;

    uint8_t *out_buf = NULL;
    int out_len = 0;
    int rc = hedgehog_mp3_to_opus_mem((const uint8_t *)in_ptr, (size_t)len, &out_buf, &out_len);

    (*env)->ReleaseByteArrayElements(env, mp3_bytes, in_ptr, JNI_ABORT);

    if (rc != 0 || !out_buf || out_len <= 0) {
        if (out_buf) av_free(out_buf);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, out_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, out_len, (const jbyte *)out_buf);
    }
    av_free(out_buf);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dm1sh_hedgehog_tts_OpusConverter_nativeConvertWavToMp3(
    JNIEnv *env,
    jclass clazz,
    jbyteArray wav_bytes,
    jint kbps
) {
    (void)clazz;
    if (!wav_bytes) return NULL;
    jsize len = (*env)->GetArrayLength(env, wav_bytes);
    if (len <= 0) return NULL;

    jbyte *in_ptr = (*env)->GetByteArrayElements(env, wav_bytes, NULL);
    if (!in_ptr) return NULL;

    uint8_t *out_buf = NULL;
    int out_len = 0;
    int rc = hedgehog_wav_to_mp3_mem((const uint8_t *)in_ptr, (size_t)len, (int)kbps, &out_buf, &out_len);

    (*env)->ReleaseByteArrayElements(env, wav_bytes, in_ptr, JNI_ABORT);

    if (rc != 0 || !out_buf || out_len <= 0) {
        if (out_buf) av_free(out_buf);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, out_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, out_len, (const jbyte *)out_buf);
    }
    av_free(out_buf);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_dm1sh_hedgehog_tts_OpusConverter_nativeConvertWavFilesToMp3(
    JNIEnv *env,
    jclass clazz,
    jobjectArray input_paths,
    jbyteArray intro_mp3,
    jint kbps
) {
    (void)clazz;
    if (!input_paths) return NULL;
    jsize count = (*env)->GetArrayLength(env, input_paths);
    if (count <= 0) return NULL;

    const char **in_cstrs = (const char **)calloc((size_t)count, sizeof(char *));
    jstring *jstrs = (jstring *)calloc((size_t)count, sizeof(jstring));
    if (!in_cstrs || !jstrs) {
        free(in_cstrs);
        free(jstrs);
        return NULL;
    }

    for (jsize i = 0; i < count; i++) {
        jstrs[i] = (jstring)(*env)->GetObjectArrayElement(env, input_paths, i);
        in_cstrs[i] = jstrs[i] ? (*env)->GetStringUTFChars(env, jstrs[i], NULL) : "";
    }

    jbyte *intro_ptr = NULL;
    jsize intro_len = 0;
    if (intro_mp3) {
        intro_len = (*env)->GetArrayLength(env, intro_mp3);
        if (intro_len > 0) {
            intro_ptr = (*env)->GetByteArrayElements(env, intro_mp3, NULL);
        }
    }

    uint8_t *out_buf = NULL;
    int out_len = 0;
    int rc = hedgehog_wav_files_to_mp3(
        (int)count,
        in_cstrs,
        (const uint8_t *)intro_ptr,
        intro_ptr ? (size_t)intro_len : 0,
        (int)kbps,
        &out_buf,
        &out_len
    );

    if (intro_ptr) {
        (*env)->ReleaseByteArrayElements(env, intro_mp3, intro_ptr, JNI_ABORT);
    }
    for (jsize i = 0; i < count; i++) {
        if (jstrs[i] && in_cstrs[i]) {
            (*env)->ReleaseStringUTFChars(env, jstrs[i], in_cstrs[i]);
        }
    }
    free(in_cstrs);
    free(jstrs);

    if (rc != 0 || !out_buf || out_len <= 0) {
        if (out_buf) av_free(out_buf);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, out_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, out_len, (const jbyte *)out_buf);
    }
    av_free(out_buf);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_dm1sh_hedgehog_tts_OpusConverter_nativeMergeOpusFiles(
    JNIEnv *env,
    jclass clazz,
    jobjectArray input_paths,
    jstring output_path
) {
    (void)clazz;
    if (!input_paths || !output_path) return JNI_FALSE;
    jsize count = (*env)->GetArrayLength(env, input_paths);
    if (count <= 0) return JNI_FALSE;

    const char *out_cstr = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!out_cstr) return JNI_FALSE;

    const char **in_cstrs = (const char **)calloc((size_t)count, sizeof(char *));
    jstring *jstrs = (jstring *)calloc((size_t)count, sizeof(jstring));
    if (!in_cstrs || !jstrs) {
        free(in_cstrs);
        free(jstrs);
        (*env)->ReleaseStringUTFChars(env, output_path, out_cstr);
        return JNI_FALSE;
    }

    for (jsize i = 0; i < count; i++) {
        jstrs[i] = (jstring)(*env)->GetObjectArrayElement(env, input_paths, i);
        in_cstrs[i] = jstrs[i] ? (*env)->GetStringUTFChars(env, jstrs[i], NULL) : "";
    }

    int rc = hedgehog_merge_opus_files((int)count, in_cstrs, out_cstr);

    for (jsize i = 0; i < count; i++) {
        if (jstrs[i] && in_cstrs[i]) {
            (*env)->ReleaseStringUTFChars(env, jstrs[i], in_cstrs[i]);
        }
    }
    free(in_cstrs);
    free(jstrs);
    (*env)->ReleaseStringUTFChars(env, output_path, out_cstr);

    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}
