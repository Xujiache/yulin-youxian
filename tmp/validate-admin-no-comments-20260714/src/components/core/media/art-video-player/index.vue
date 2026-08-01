
<template>
  <div :id="playerId" />
</template>

<script setup lang="ts">
import Player from 'xgplayer';
import 'xgplayer/dist/index.min.css';
defineOptions({ name: 'ArtVideoPlayer' });
interface Props {
    playerId: string;
    videoUrl: string;
    posterUrl: string;
    autoplay?: boolean;
    volume?: number;
    playbackRates?: number[];
    loop?: boolean;
    muted?: boolean;
    commonStyle?: VideoPlayerStyle;
}
const props = withDefaults(defineProps<Props>(), {
    playerId: '',
    videoUrl: '',
    posterUrl: '',
    autoplay: false,
    volume: 1,
    loop: false,
    muted: false
});
const playerInstance = ref<Player | null>(null);
interface VideoPlayerStyle {
    progressColor?: string;
    playedColor?: string;
    cachedColor?: string;
    sliderBtnStyle?: Record<string, string>;
    volumeColor?: string;
}
const defaultStyle: VideoPlayerStyle = {
    progressColor: 'rgba(255, 255, 255, 0.3)',
    playedColor: '#00AEED',
    cachedColor: 'rgba(255, 255, 255, 0.6)',
    sliderBtnStyle: {
        width: '10px',
        height: '10px',
        backgroundColor: '#00AEED'
    },
    volumeColor: '#00AEED'
};
onMounted(() => {
    playerInstance.value = new Player({
        id: props.playerId,
        lang: 'zh',
        volume: props.volume,
        autoplay: props.autoplay,
        screenShot: true,
        url: props.videoUrl,
        poster: props.posterUrl,
        fluid: true,
        playbackRate: props.playbackRates,
        loop: props.loop,
        muted: props.muted,
        commonStyle: {
            ...defaultStyle,
            ...props.commonStyle
        }
    });
    playerInstance.value.on('play', () => {
        console.log('Video is playing');
    });
    playerInstance.value.on('pause', () => {
        console.log('Video is paused');
    });
    playerInstance.value.on('error', (error) => {
        console.error('Error occurred:', error);
    });
});
onBeforeUnmount(() => {
    if (playerInstance.value) {
        playerInstance.value.destroy();
    }
});
</script>
