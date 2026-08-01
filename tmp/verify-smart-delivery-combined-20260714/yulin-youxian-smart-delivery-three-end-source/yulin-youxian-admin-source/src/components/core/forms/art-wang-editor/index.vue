
<template>
  <div class="editor-wrapper">
    <Toolbar
      class="editor-toolbar"
      :editor="editorRef"
      :mode="mode"
      :defaultConfig="toolbarConfig"
    />
    <Editor
      :style="{ height: height, overflowY: 'hidden' }"
      v-model="modelValue"
      :mode="mode"
      :defaultConfig="editorConfig"
      @onCreated="onCreateEditor"
    />
  </div>
</template>

<script setup lang="ts">
import '@wangeditor/editor/dist/css/style.css';
import { onBeforeUnmount, onMounted, shallowRef, computed } from 'vue';
import { Editor, Toolbar } from '@wangeditor/editor-for-vue';
import { useUserStore } from '@/store/modules/user';
import EmojiText from '@/utils/ui/emojo';
import { IDomEditor, IToolbarConfig, IEditorConfig } from '@wangeditor/editor';
defineOptions({ name: 'ArtWangEditor' });
interface Props {
    height?: string;
    toolbarKeys?: string[];
    insertKeys?: {
        index: number;
        keys: string[];
    };
    excludeKeys?: string[];
    mode?: 'default' | 'simple';
    placeholder?: string;
    uploadConfig?: {
        maxFileSize?: number;
        maxNumberOfFiles?: number;
        server?: string;
    };
}
const props = withDefaults(defineProps<Props>(), {
    height: '500px',
    mode: 'default',
    placeholder: '请输入内容...',
    excludeKeys: () => ['fontFamily']
});
const modelValue = defineModel<string>({ required: true });
const editorRef = shallowRef<IDomEditor>();
const userStore = useUserStore();
const DEFAULT_UPLOAD_CONFIG = {
    maxFileSize: 3 * 1024 * 1024,
    maxNumberOfFiles: 10,
    fieldName: 'file',
    allowedFileTypes: ['image/*']
} as const;
const uploadServer = computed(() => props.uploadConfig?.server || `${import.meta.env.VITE_API_URL}/api/common/upload/wangeditor`);
const mergedUploadConfig = computed(() => ({
    ...DEFAULT_UPLOAD_CONFIG,
    ...props.uploadConfig
}));
const toolbarConfig = computed((): Partial<IToolbarConfig> => {
    const config: Partial<IToolbarConfig> = {};
    if (props.toolbarKeys && props.toolbarKeys.length > 0) {
        config.toolbarKeys = props.toolbarKeys;
    }
    if (props.insertKeys) {
        config.insertKeys = props.insertKeys;
    }
    if (props.excludeKeys && props.excludeKeys.length > 0) {
        config.excludeKeys = props.excludeKeys;
    }
    return config;
});
const editorConfig: Partial<IEditorConfig> = {
    placeholder: props.placeholder,
    MENU_CONF: {
        uploadImage: {
            fieldName: mergedUploadConfig.value.fieldName,
            maxFileSize: mergedUploadConfig.value.maxFileSize,
            maxNumberOfFiles: mergedUploadConfig.value.maxNumberOfFiles,
            allowedFileTypes: mergedUploadConfig.value.allowedFileTypes,
            server: uploadServer.value,
            headers: {
                Authorization: userStore.accessToken
            },
            onSuccess() {
                ElMessage.success(`图片上传成功 ${EmojiText[200]}`);
            },
            onError(file: File, err: any, res: any) {
                console.error('图片上传失败:', err, res);
                ElMessage.error(`图片上传失败 ${EmojiText[500]}`);
            }
        }
    }
};
const onCreateEditor = (editor: IDomEditor) => {
    editorRef.value = editor;
    editor.on('fullScreen', () => {
        console.log('编辑器进入全屏模式');
    });
    applyCustomIcons();
};
const applyCustomIcons = () => {
    let retryCount = 0;
    const maxRetries = 10;
    const retryDelay = 100;
    const tryApplyIcons = () => {
        const editor = editorRef.value;
        if (!editor) {
            if (retryCount < maxRetries) {
                retryCount++;
                setTimeout(tryApplyIcons, retryDelay);
            }
            return;
        }
        const editorContainer = editor.getEditableContainer().closest('.editor-wrapper');
        if (!editorContainer) {
            if (retryCount < maxRetries) {
                retryCount++;
                setTimeout(tryApplyIcons, retryDelay);
            }
            return;
        }
        const toolbar = editorContainer.querySelector('.w-e-toolbar');
        const toolbarButtons = editorContainer.querySelectorAll('.w-e-bar-item button[data-menu-key]');
        if (toolbar && toolbarButtons.length > 0) {
            return;
        }
        if (retryCount < maxRetries) {
            retryCount++;
            setTimeout(tryApplyIcons, retryDelay);
        }
        else {
            console.warn('工具栏渲染超时，无法应用自定义图标 - 编辑器实例:', editor.id);
        }
    };
    requestAnimationFrame(tryApplyIcons);
};
defineExpose({
    getEditor: () => editorRef.value,
    setHtml: (html: string) => editorRef.value?.setHtml(html),
    getHtml: () => editorRef.value?.getHtml(),
    clear: () => editorRef.value?.clear(),
    focus: () => editorRef.value?.focus()
});
onMounted(() => {
});
onBeforeUnmount(() => {
    const editor = editorRef.value;
    if (editor) {
        editor.destroy();
    }
});
</script>

<style lang="scss">

  @use './style';
</style>
