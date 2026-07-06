import { createApp } from "vue";
import { createPinia } from "pinia";
import {
  ElAlert,
  ElAside,
  ElButton,
  ElCol,
  ElContainer,
  ElDialog,
  ElForm,
  ElFormItem,
  ElHeader,
  ElImage,
  ElInput,
  ElInputNumber,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElRow,
  ElSegmented,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElUpload
} from "element-plus";
import "element-plus/dist/index.css";
import "./styles/global.css";
import App from "./App.vue";
import router from "./router";

const elementComponents = [
  ElAlert,
  ElAside,
  ElButton,
  ElCol,
  ElContainer,
  ElDialog,
  ElForm,
  ElFormItem,
  ElHeader,
  ElImage,
  ElInput,
  ElInputNumber,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElOption,
  ElRow,
  ElSegmented,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
  ElUpload
];

const app = createApp(App);

elementComponents.forEach((component) => {
  app.component(component.name, component);
});

app
  .use(createPinia())
  .use(router)
  .mount("#app");
