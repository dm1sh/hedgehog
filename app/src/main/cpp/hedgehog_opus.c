#include <jni.h>
#include <stdint.h>
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

/*
 * Transcodes an in-memory MP3 buffer to an in-memory .opus (Ogg Opus) buffer
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
    av_log_set_level(AV_LOG_ERROR);

    int ret = -1;
    AVFormatContext *in_fmt_ctx = NULL;
    AVIOContext *in_avio_ctx = NULL;
    uint8_t *in_avio_buf = NULL;
    AVCodecContext *dec_ctx = NULL;
    AVFormatContext *out_fmt_ctx = NULL;
    AVCodecContext *enc_ctx = NULL;
    SwrContext *swr_ctx = NULL;
    AVAudioFifo *fifo = NULL;
    AVPacket *pkt = NULL;
    AVPacket *out_pkt = NULL;
    AVFrame *in_frame = NULL;
    AVFrame *enc_frame = NULL;
    int audio_stream_idx = -1;
    int64_t next_pts = 0;

    BufferReader br = { in_data, in_size, 0 };
    in_avio_buf = (uint8_t *)av_malloc(32768);
    if (!in_avio_buf) goto cleanup;

    in_avio_ctx = avio_alloc_context(in_avio_buf, 32768, 0, &br, read_packet, NULL, seek_packet);
    if (!in_avio_ctx) goto cleanup;

    in_fmt_ctx = avformat_alloc_context();
    if (!in_fmt_ctx) goto cleanup;
    in_fmt_ctx->pb = in_avio_ctx;
    in_fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

    if (avformat_open_input(&in_fmt_ctx, NULL, NULL, NULL) < 0) goto cleanup;
    if (avformat_find_stream_info(in_fmt_ctx, NULL) < 0) goto cleanup;

    for (unsigned int i = 0; i < in_fmt_ctx->nb_streams; i++) {
        if (in_fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audio_stream_idx = (int)i;
            break;
        }
    }
    if (audio_stream_idx < 0) goto cleanup;

    AVCodecParameters *in_codecpar = in_fmt_ctx->streams[audio_stream_idx]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(in_codecpar->codec_id);
    if (!decoder) goto cleanup;

    dec_ctx = avcodec_alloc_context3(decoder);
    if (!dec_ctx) goto cleanup;
    if (avcodec_parameters_to_context(dec_ctx, in_codecpar) < 0) goto cleanup;
    if (avcodec_open2(dec_ctx, decoder, NULL) < 0) goto cleanup;

    if (avformat_alloc_output_context2(&out_fmt_ctx, NULL, "opus", NULL) < 0 || !out_fmt_ctx) {
        goto cleanup;
    }

    if (avio_open_dyn_buf(&out_fmt_ctx->pb) < 0) goto cleanup;

    const AVCodec *encoder = avcodec_find_encoder_by_name("libopus");
    if (!encoder) encoder = avcodec_find_encoder(AV_CODEC_ID_OPUS);
    if (!encoder) goto cleanup;

    AVStream *out_stream = avformat_new_stream(out_fmt_ctx, NULL);
    if (!out_stream) goto cleanup;

    enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) goto cleanup;

    enc_ctx->sample_rate = 48000;
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

    if (avcodec_open2(enc_ctx, encoder, NULL) < 0) goto cleanup;
    if (avcodec_parameters_from_context(out_stream->codecpar, enc_ctx) < 0) goto cleanup;
    out_stream->time_base = enc_ctx->time_base;

    if (avformat_write_header(out_fmt_ctx, NULL) < 0) goto cleanup;

    if (dec_ctx->ch_layout.nb_channels <= 0) {
        av_channel_layout_default(&dec_ctx->ch_layout, 1);
    }

    if (swr_alloc_set_opts2(
            &swr_ctx,
            &enc_ctx->ch_layout,
            enc_ctx->sample_fmt,
            enc_ctx->sample_rate,
            &dec_ctx->ch_layout,
            dec_ctx->sample_fmt,
            dec_ctx->sample_rate,
            0,
            NULL) < 0) {
        goto cleanup;
    }
    if (swr_init(swr_ctx) < 0) goto cleanup;

    int frame_size = enc_ctx->frame_size > 0 ? enc_ctx->frame_size : 2880; /* 60ms @ 48kHz = 2880 */
    fifo = av_audio_fifo_alloc(enc_ctx->sample_fmt, enc_ctx->ch_layout.nb_channels, frame_size * 4);
    if (!fifo) goto cleanup;

    pkt = av_packet_alloc();
    out_pkt = av_packet_alloc();
    in_frame = av_frame_alloc();
    enc_frame = av_frame_alloc();
    if (!pkt || !out_pkt || !in_frame || !enc_frame) goto cleanup;

    #define DRAIN_FIFO(flush_all) \
        while (av_audio_fifo_size(fifo) >= frame_size || ((flush_all) && av_audio_fifo_size(fifo) > 0)) { \
            int cur_size = av_audio_fifo_size(fifo); \
            if (cur_size > frame_size) cur_size = frame_size; \
            av_frame_unref(enc_frame); \
            enc_frame->nb_samples = frame_size; \
            av_channel_layout_copy(&enc_frame->ch_layout, &enc_ctx->ch_layout); \
            enc_frame->format = enc_ctx->sample_fmt; \
            enc_frame->sample_rate = enc_ctx->sample_rate; \
            if (av_frame_get_buffer(enc_frame, 0) < 0) goto cleanup; \
            if (cur_size < frame_size) { \
                av_samples_set_silence(enc_frame->data, cur_size, frame_size - cur_size, enc_ctx->ch_layout.nb_channels, enc_ctx->sample_fmt); \
            } \
            if (av_audio_fifo_read(fifo, (void **)enc_frame->data, cur_size) < cur_size) goto cleanup; \
            enc_frame->pts = next_pts; \
            next_pts += frame_size; \
            if (avcodec_send_frame(enc_ctx, enc_frame) < 0) goto cleanup; \
            while (avcodec_receive_packet(enc_ctx, out_pkt) == 0) { \
                av_packet_rescale_ts(out_pkt, enc_ctx->time_base, out_stream->time_base); \
                out_pkt->stream_index = out_stream->index; \
                if (av_interleaved_write_frame(out_fmt_ctx, out_pkt) < 0) goto cleanup; \
                av_packet_unref(out_pkt); \
            } \
        }

    while (av_read_frame(in_fmt_ctx, pkt) >= 0) {
        if (pkt->stream_index == audio_stream_idx) {
            if (avcodec_send_packet(dec_ctx, pkt) >= 0) {
                while (avcodec_receive_frame(dec_ctx, in_frame) == 0) {
                    int max_out = swr_get_out_samples(swr_ctx, in_frame->nb_samples);
                    if (max_out > 0) {
                        uint8_t **converted = NULL;
                        if (av_samples_alloc_array_and_samples(&converted, NULL, enc_ctx->ch_layout.nb_channels, max_out, enc_ctx->sample_fmt, 0) >= 0) {
                            int conv_samples = swr_convert(swr_ctx, converted, max_out, (const uint8_t **)in_frame->extended_data, in_frame->nb_samples);
                            if (conv_samples > 0) {
                                av_audio_fifo_write(fifo, (void **)converted, conv_samples);
                            }
                            if (converted[0]) av_freep(&converted[0]);
                            av_freep(&converted);
                        }
                    }
                    av_frame_unref(in_frame);
                    DRAIN_FIFO(0);
                }
            }
        }
        av_packet_unref(pkt);
    }

    avcodec_send_packet(dec_ctx, NULL);
    while (avcodec_receive_frame(dec_ctx, in_frame) == 0) {
        int max_out = swr_get_out_samples(swr_ctx, in_frame->nb_samples);
        if (max_out > 0) {
            uint8_t **converted = NULL;
            if (av_samples_alloc_array_and_samples(&converted, NULL, enc_ctx->ch_layout.nb_channels, max_out, enc_ctx->sample_fmt, 0) >= 0) {
                int conv_samples = swr_convert(swr_ctx, converted, max_out, (const uint8_t **)in_frame->extended_data, in_frame->nb_samples);
                if (conv_samples > 0) {
                    av_audio_fifo_write(fifo, (void **)converted, conv_samples);
                }
                if (converted[0]) av_freep(&converted[0]);
                av_freep(&converted);
            }
        }
        av_frame_unref(in_frame);
    }

    int delayed = swr_get_out_samples(swr_ctx, 0);
    if (delayed > 0) {
        uint8_t **converted = NULL;
        if (av_samples_alloc_array_and_samples(&converted, NULL, enc_ctx->ch_layout.nb_channels, delayed, enc_ctx->sample_fmt, 0) >= 0) {
            int conv_samples = swr_convert(swr_ctx, converted, delayed, NULL, 0);
            if (conv_samples > 0) {
                av_audio_fifo_write(fifo, (void **)converted, conv_samples);
            }
            if (converted[0]) av_freep(&converted[0]);
            av_freep(&converted);
        }
    }

    DRAIN_FIFO(1);

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
    if (enc_frame) av_frame_free(&enc_frame);
    if (in_frame) av_frame_free(&in_frame);
    if (out_pkt) av_packet_free(&out_pkt);
    if (pkt) av_packet_free(&pkt);
    if (fifo) av_audio_fifo_free(fifo);
    if (swr_ctx) swr_free(&swr_ctx);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (out_fmt_ctx) avformat_free_context(out_fmt_ctx);
    if (dec_ctx) avcodec_free_context(&dec_ctx);
    if (in_fmt_ctx) avformat_close_input(&in_fmt_ctx);
    if (in_avio_ctx) {
        av_freep(&in_avio_ctx->buffer);
        avio_context_free(&in_avio_ctx);
    }
    return ret;
}

/*
 * Remuxes/concatenates multiple .opus files into a single continuous .opus file
 * without re-encoding (stream copy with monotonic PTS/DTS).
 */
int hedgehog_merge_opus_files(int num_files, const char **input_paths, const char *output_path) {
    if (num_files <= 0 || !input_paths || !output_path) return -1;
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

    for (int i = 0; i < num_files; i++) {
        AVFormatContext *in_fmt_ctx = NULL;
        if (avformat_open_input(&in_fmt_ctx, input_paths[i], NULL, NULL) < 0) {
            continue;
        }
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

    int sample_rate = 24000;
    const uint8_t *pcm_ptr = in_data;
    size_t pcm_bytes = in_size;

    if (in_size >= 44 &&
        memcmp(in_data, "RIFF", 4) == 0 &&
        memcmp(in_data + 8, "WAVE", 4) == 0) {
        size_t pos = 12;
        int found_data = 0;
        while (pos + 8 <= in_size) {
            uint32_t chunk_size =
                ((uint32_t)in_data[pos + 4]) |
                (((uint32_t)in_data[pos + 5]) << 8) |
                (((uint32_t)in_data[pos + 6]) << 16) |
                (((uint32_t)in_data[pos + 7]) << 24);
            size_t chunk_data_pos = pos + 8;
            if (memcmp(in_data + pos, "fmt ", 4) == 0 && chunk_size >= 16 && chunk_data_pos + 8 <= in_size) {
                uint32_t sr =
                    ((uint32_t)in_data[chunk_data_pos + 4]) |
                    (((uint32_t)in_data[chunk_data_pos + 5]) << 8) |
                    (((uint32_t)in_data[chunk_data_pos + 6]) << 16) |
                    (((uint32_t)in_data[chunk_data_pos + 7]) << 24);
                if (sr >= 8000 && sr <= 96000) {
                    sample_rate = (int)sr;
                }
            } else if (memcmp(in_data + pos, "data", 4) == 0) {
                size_t avail = in_size - chunk_data_pos;
                pcm_ptr = in_data + chunk_data_pos;
                pcm_bytes = (chunk_size <= avail) ? (size_t)chunk_size : avail;
                found_data = 1;
                break;
            }
            size_t step = (size_t)chunk_size + (chunk_size & 1u);
            if (step > in_size - chunk_data_pos) break;
            pos = chunk_data_pos + step;
        }
        if (!found_data) {
            pcm_ptr = in_data + 44;
            pcm_bytes = in_size - 44;
        }
    }

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
