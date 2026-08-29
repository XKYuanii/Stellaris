<template>
  <Header></Header>
  <el-row>
    <el-form ref="authRef" :model="authForm" :rules="authRules" class="login-form">
      <el-col :span="24">
        <el-form-item label="请输入真实姓名:" prop="relName">
          <el-input
              v-model="authForm.relName"
              class="input-with-select"
              type="text"
              maxlength="30"
              autocomplete="name"
          ></el-input>
        </el-form-item>
        <el-form-item label="请输入身份证号码:" prop="idNumber">
          <el-input
              v-model="authForm.idNumber"
              class="input-with-select"
              type="password"
              show-password
              maxlength="18"
              autocomplete="off"
          ></el-input>
        </el-form-item>
      </el-col>
      <el-button
          size="large"
          type="primary"
          class="btn"
          @click.prevent="savePsd"
      ><span>保存</span></el-button>
    </el-form>
  </el-row>
  <Footer class="foot"></Footer>
</template>

<script setup>

import Header from '../../../components/header/index'
import Footer from '../../../components/footer/index'
import {getEditPsd} from '@/api/accountSettings'
import {ElMessage} from "element-plus"
import {getUserIdKey} from "../../../utils/auth"
import {ref, reactive} from 'vue'
import {useRouter} from 'vue-router'
import useUserStore from '@/store/modules/user'
import {getAuthentication} from "../../../api/accountSettings";


const router = useRouter();
const userStore = useUserStore()
const authRef = ref(null)
const authForm = ref({
  idNumber: '',
  relName: '',
  id: getUserIdKey()
})


const authRules = reactive({
      idNumber: [
        { required: true, message: '请输入身份证号码', trigger: 'blur' },
        { pattern: /(^\d{15}$)|(^\d{17}[0-9Xx]$)/, message: '身份证号码格式不正确', trigger: 'blur' }
      ],
      relName: [
        { required: true, message: '请输入真实姓名', trigger: 'blur' },
        { pattern: /^[\u3400-\u9FFF·]{2,30}$/, message: '请输入正确的中文姓名', trigger: 'blur' }
      ],
    }
)


function savePsd() {
  authRef.value.validate((valid) => {
    if (!valid) return
    const request = {
      ...authForm.value,
      relName: authForm.value.relName.trim(),
      idNumber: authForm.value.idNumber.trim().toUpperCase()
    }
    getAuthentication(request).then(response => {
    if (response.code == '0') {
      ElMessage({
        message: '保存成功',
        type: 'success',
      })

      userStore.logOut().then(() => {
        location.href = '../../login';
      })

    } else {
      ElMessage({
        message: response.message,
        type: 'error',
      })
    }
    }).catch(error => {
      ElMessage.error(error?.message || '实名认证提交失败，请稍后重试')
    })
  })
}
</script>

<style scoped lang="scss">
.el-row {
  width: 400px;
  height: 400px;
  margin: 100px auto 30px;
}

.btn {
  margin-left: 130px;
  background: rgba(255, 55, 29, 0.85);
  border: none;
}
</style>
