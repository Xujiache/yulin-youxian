
<template>
  <div class="cutter-container">
    <div class="cutter-component">
      <div class="title">{{ title }}</div>
      <ImgCutter
        ref="imgCutterModal"
        @cutDown="cutDownImg"
        @onPrintImg="cutterPrintImg"
        @onImageLoadComplete="handleImageLoadComplete"
        @onImageLoadError="handleImageLoadError"
        @onClearAll="handleClearAll"
        v-bind="cutterProps"
        class="img-cutter"
      >
        <template #choose>
          <ElButton type="primary" plain v-ripple>选择图片</ElButton>
        </template>
        <template #cancel>
          <ElButton type="danger" plain v-ripple>清除</ElButton>
        </template>
        <template #confirm>
          
          <div></div>
        </template>
      </ImgCutter>
    </div>

    <div v-if="showPreview" class="preview-container">
      <div class="title">{{ previewTitle }}</div>
      <div
        class="preview-box"
        :style="{
          width: `${cutterProps.cutWidth}px`,
          height: `${cutterProps.cutHeight}px`
        }"
      >
        <img class="preview-img" :src="temImgPath" alt="预览图" v-if="temImgPath" />
      </div>
      <ElButton class="download-btn" @click="downloadImg" :disabled="!temImgPath" v-ripple
        >下载图片</ElButton
      >
    </div>
  </div>
</template>

<script setup lang="ts">
import ImgCutter from 'vue-img-cutter';
defineOptions({ name: 'ArtCutterImg' });
interface CutterProps {
    isModal?: boolean;
    tool?: boolean;
    toolBgc?: string;
    title?: string;
    previewTitle?: string;
    showPreview?: boolean;
    boxWidth?: number;
    boxHeight?: number;
    cutWidth?: number;
    cutHeight?: number;
    sizeChange?: boolean;
    moveAble?: boolean;
    imgMove?: boolean;
    scaleAble?: boolean;
    originalGraph?: boolean;
    crossOrigin?: boolean;
    fileType?: 'png' | 'jpeg' | 'webp';
    quality?: number;
    watermarkText?: string;
    watermarkFontSize?: number;
    watermarkColor?: string;
    saveCutPosition?: boolean;
    previewMode?: boolean;
    imgUrl?: string;
}
interface CutterResult {
    fileName: string;
    file: File;
    blob: Blob;
    dataURL: string;
}
const props = withDefaults(defineProps<CutterProps>(), {
    isModal: false,
    tool: true,
    toolBgc: '#fff',
    title: '',
    previewTitle: '',
    showPreview: true,
    boxWidth: 700,
    boxHeight: 458,
    cutWidth: 470,
    cutHeight: 270,
    sizeChange: true,
    moveAble: true,
    imgMove: true,
    scaleAble: true,
    originalGraph: true,
    crossOrigin: true,
    fileType: 'png',
    quality: 0.9,
    watermarkText: '',
    watermarkFontSize: 20,
    watermarkColor: '#ffffff',
    saveCutPosition: true,
    previewMode: true
});
const emit = defineEmits(['update:imgUrl', 'error', 'imageLoadComplete', 'imageLoadError']);
const temImgPath = ref('');
const imgCutterModal = ref();
const cutterProps = computed(() => ({
    ...props,
    WatermarkText: props.watermarkText,
    WatermarkFontSize: props.watermarkFontSize,
    WatermarkColor: props.watermarkColor
}));
function preloadImage(url: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const img = new Image();
        img.crossOrigin = 'anonymous';
        img.onload = () => resolve();
        img.onerror = reject;
        img.src = url;
    });
}
async function initImgCutter() {
    if (props.imgUrl) {
        try {
            await preloadImage(props.imgUrl);
            imgCutterModal.value?.handleOpen({
                name: '封面图片',
                src: props.imgUrl
            });
        }
        catch (error) {
            emit('error', error);
            console.error('图片加载失败:', error);
        }
    }
}
onMounted(() => {
    if (props.imgUrl) {
        temImgPath.value = props.imgUrl;
        initImgCutter();
    }
});
watch(() => props.imgUrl, (newVal) => {
    if (newVal) {
        temImgPath.value = newVal;
        initImgCutter();
    }
});
function cutterPrintImg(result: {
    dataURL: string;
}) {
    temImgPath.value = result.dataURL;
}
function cutDownImg(result: CutterResult) {
    emit('update:imgUrl', result.dataURL);
}
function handleImageLoadComplete(result: any) {
    emit('imageLoadComplete', result);
}
function handleImageLoadError(error: any) {
    emit('error', error);
    emit('imageLoadError', error);
}
function handleClearAll() {
    temImgPath.value = '';
}
function downloadImg() {
    console.log('下载图片');
    const a = document.createElement('a');
    a.href = temImgPath.value;
    a.download = 'image.png';
    a.click();
}
</script>

<style lang="scss" scoped>

  .cutter-container {
    display: flex;
    flex-flow: row wrap;

    .title {
      padding-bottom: 10px;
      font-size: 18px;
      font-weight: 500;
    }

    .cutter-component {
      margin-right: 30px;
    }

    .preview-container {
      .preview-box {
        background-color: var(--art-active-color) !important;

        .preview-img {
          width: 100%;
          height: 100%;
          object-fit: contain;
        }
      }

      .download-btn {
        display: block;
        margin: 20px auto;
      }
    }

    :deep(.toolBoxControl) {
      z-index: 100;
    }

    :deep(.dockMain) {
      right: 0;
      bottom: -40px;
      left: 0;
      z-index: 10;
      padding: 0;
      background-color: transparent !important;
      opacity: 1;
    }

    :deep(.copyright) {
      display: none !important;
    }

    :deep(.i-dialog-footer) {
      margin-top: 60px !important;
    }

    :deep(.dockBtn) {
      height: 26px;
      padding: 0 10px;
      font-size: 12px;
      line-height: 26px;
      color: var(--el-color-primary) !important;
      background-color: var(--el-color-primary-light-9) !important;
      border: 1px solid var(--el-color-primary-light-4) !important;
    }

    :deep(.dockBtnScrollBar) {
      margin: 0 10px 0 6px;
      background-color: var(--el-color-primary-light-1);
    }

    :deep(.scrollBarControl) {
      border-color: var(--el-color-primary);
    }

    :deep(.closeIcon) {
      line-height: 15px !important;
    }
  }

  .dark {
    .cutter-container {
      :deep(.toolBox) {
        border: transparent;
      }

      :deep(.dialogMain) {
        background-color: transparent !important;
      }

      :deep(.i-dialog-footer) {
        .btn {
          background-color: var(--el-color-primary) !important;
          border: transparent;
        }
      }
    }
  }
</style>
