
<template>
  <div class="flex w-full h-screen">
    <LoginLeftView />

    <div class="relative flex-1">
      <AuthTopBar />

      <div class="auth-right-wrap">
        <div class="form">
          <h3 class="title">{{ $t('login.title') }}</h3>
          <p class="sub-title">{{ $t('login.subTitle') }}</p>
          <ElForm
            ref="formRef"
            :model="formData"
            :rules="rules"
            :key="formKey"
            @keyup.enter="handleSubmit"
            style="margin-top: 25px"
          >
            <ElFormItem prop="username">
              <ElInput
                class="custom-height"
                :placeholder="$t('login.placeholder.username')"
                v-model.trim="formData.username"
              />
            </ElFormItem>
            <ElFormItem prop="password">
              <ElInput
                class="custom-height"
                :placeholder="$t('login.placeholder.password')"
                v-model.trim="formData.password"
                type="password"
                autocomplete="off"
                show-password
              />
            </ElFormItem>

            <div class="flex-cb mt-2 text-sm">
              <ElCheckbox v-model="formData.rememberPassword">{{
                $t('login.rememberPwd')
              }}</ElCheckbox>
              <RouterLink class="text-theme" :to="{ name: 'ForgetPassword' }">{{
                $t('login.forgetPwd')
              }}</RouterLink>
            </div>

            <div style="margin-top: 30px">
              <ElButton
                class="w-full custom-height"
                type="primary"
                @click="handleSubmit"
                :loading="loading"
                v-ripple
              >
                {{ $t('login.btnText') }}
              </ElButton>
            </div>

            <div class="mt-5 text-sm text-gray-600">
              <span>{{ $t('login.noAccount') }}</span>
              <RouterLink class="text-theme" :to="{ name: 'Register' }">{{
                $t('login.register')
              }}</RouterLink>
            </div>
          </ElForm>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import AppConfig from '@/config';
import { useUserStore } from '@/store/modules/user';
import { useI18n } from 'vue-i18n';
import { HttpError } from '@/utils/http/error';
import { fetchLogin } from '@/api/auth';
import { ElNotification, type FormInstance, type FormRules } from 'element-plus';
defineOptions({ name: 'Login' });
const { t, locale } = useI18n();
const formKey = ref(0);
watch(locale, () => {
    formKey.value++;
});
const userStore = useUserStore();
const router = useRouter();
const route = useRoute();
const systemName = AppConfig.systemInfo.name;
const formRef = ref<FormInstance>();
const formData = reactive({
    username: '',
    password: '',
    rememberPassword: true
});
const rules = computed<FormRules>(() => ({
    username: [{ required: true, message: t('login.placeholder.username'), trigger: 'blur' }],
    password: [{ required: true, message: t('login.placeholder.password'), trigger: 'blur' }]
}));
const loading = ref(false);
const handleSubmit = async () => {
    if (!formRef.value)
        return;
    try {
        const valid = await formRef.value.validate();
        if (!valid)
            return;
        loading.value = true;
        const { username, password } = formData;
        const { token, refreshToken } = await fetchLogin({
            username,
            password
        });
        if (!token) {
            throw new Error('Login failed - no token received');
        }
        userStore.setToken(token, refreshToken);
        userStore.setLoginStatus(true);
        showLoginSuccessNotice();
        const redirect = route.query.redirect as string;
        router.push(redirect || '/');
    }
    catch (error) {
        if (error instanceof HttpError) {
        }
        else {
            console.error('[Login] Unexpected error:', error);
        }
    }
    finally {
        loading.value = false;
    }
};
const showLoginSuccessNotice = () => {
    setTimeout(() => {
        ElNotification({
            title: t('login.success.title'),
            type: 'success',
            duration: 2500,
            zIndex: 10000,
            message: `${t('login.success.message')}, ${systemName}!`
        });
    }, 1000);
};
</script>

<style scoped>

  @import './style.css';
</style>
