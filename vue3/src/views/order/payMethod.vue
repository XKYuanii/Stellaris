<template>
  <div class="app-container">
    <div class="pay-header">
      <div class="back"><el-icon><ArrowLeftBold /></el-icon></div>
      <div class="content"><img :src="pay" alt=""><span>{{ channel === 'mock' ? '模拟支付' : '支付宝付款' }}</span></div>
    </div>
    <div class="pay-section">
      <el-radio-group v-model="channel" class="channel-select" :disabled="channelLocked">
        <el-radio-button label="mock">模拟支付</el-radio-button>
        <el-radio-button label="alipay">支付宝</el-radio-button>
      </el-radio-group>
      <div v-if="channel === 'mock'" class="mock-actions">
        <p>模拟渠道会走真实账单、订单、Intent 和座位状态链路，不会调用第三方平台。</p>
        <el-button type="success" :loading="paying" :disabled="!orderReady" @click="continuePay('SUCCESS')">模拟支付成功</el-button>
        <el-button type="danger" :loading="paying" :disabled="!orderReady" @click="continuePay('FAILURE')">模拟支付失败</el-button>
      </div>
      <el-button v-else type="primary" class="payContinue" :loading="paying" :disabled="!orderReady" @click="continuePay">继续浏览器付款</el-button>
    </div>
  </div>
</template>

<script setup name="PayMethod">
import pay from "@/assets/section/pay.png"
import {computed, ref,onMounted} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {getOrderDetailApi,orderPayApi} from "@/api/order.js";
import {ElMessage} from 'element-plus'
//订单编号
const orderNumber = ref('')
//订单详情数据
const orderDetailData = ref('');
const router = useRouter();
const route = useRoute();
const channel = ref('mock')
const paying = ref(false)
const channelLocked = ref(false)
const orderReady = computed(() => orderDetailData.value && orderNumber.value)

async function continuePay(simulationOutcome) {
  if (!orderReady.value || paying.value) {
    ElMessage.warning('订单信息尚未加载完成')
    return
  }
  const orderPayParams = {
    'platform':3,
    'orderNumber':orderNumber.value,
    'subject':orderDetailData.value.programTitle,
    'price':orderDetailData.value.orderPrice,
    'channel':channel.value,
    'simulationOutcome':channel.value === 'mock' ? simulationOutcome : null,
    'payBillType':1
  }
  paying.value = true
  channelLocked.value = true
  localStorage.setItem(`payChannel:${orderNumber.value}`, channel.value)
  try {
    const response = await orderPayApi(orderPayParams)
    if (String(response.code) !== '0' || !response.data) {
      ElMessage.error(response.message || '支付请求失败')
      return
    }
    const result = response.data
    if (channel.value === 'alipay' && result.state === 'INITIATED') {
      document.write(result.redirectPayload)
      return
    }
    if (result.state === 'FAILED') {
      ElMessage.error(result.message || '模拟支付失败，订单仍为未支付')
      return
    }
    localStorage.setItem('payChannelType', channel.value === 'mock' ? '3' : '1')
    localStorage.setItem('paymentState', result.state)
    if (result.state === 'PAID') ElMessage.success(result.message || '支付成功')
    else ElMessage.warning(result.message || '支付状态同步中')
    await router.replace({path:'/order/paySuccess'})
  } catch (error) {
    ElMessage.error(error?.message || '支付请求异常')
  } finally {
    paying.value = false
  }
}

//跳转后的接收值
onMounted(() => {
  getOrderDetail()
})
//订单详情方法
async function getOrderDetail() {
  const candidate = history.state?.orderNumber || route.query.orderNumber || localStorage.getItem('orderNumber')
  if (!candidate || !/^\d+$/.test(String(candidate))) {
    ElMessage.error('缺少有效订单号，请从订单列表重新进入')
    await router.replace({path:'/orderManagement/index'})
    return
  }
  orderNumber.value = String(candidate)
  const persistedChannel = localStorage.getItem(`payChannel:${orderNumber.value}`)
  if (persistedChannel === 'mock' || persistedChannel === 'alipay') {
    channel.value = persistedChannel
    channelLocked.value = true
  }
  const orderDetailParams = {'orderNumber': orderNumber.value}
  //传值-订单号
  localStorage.setItem('orderNumber',orderNumber.value)
  try {
    const response = await getOrderDetailApi(orderDetailParams)
    if (String(response.code) !== '0' || !response.data || String(response.data.orderNumber) !== orderNumber.value) {
      throw new Error(response.message || '订单详情响应无效')
    }
    orderDetailData.value = response.data
  } catch (error) {
    ElMessage.error(error?.message || '订单详情加载失败')
  }
}

</script>

<style scoped lang="scss">
.app-container {
  .pay-header {
    display: flex;
    -webkit-box-pack: justify;
    -webkit-justify-content: space-between;
    justify-content: space-between;
    -webkit-box-align: center;
    -webkit-align-items: center;
    flex-direction: row;
    align-items: center;
    height: 100%;
    padding: 0 55px;
    background-color: #fff;

    .back {
      width: 40px;
      .el-icon{
        font-size: 40px;
      }
    }

    .content {
      width: calc(100% - 40px);
      text-align: center;
      position: relative;
      img {
        width: 60px;
        height: 60px;
        position: absolute;
        top: 4px;
        left: 40%;
      }

      span {
        font-size: 40px;
        font-weight: 700;
        color: #333;
        height: 70px;
        display: inline-block;
        line-height: 70px;
        width: 200px;
        margin-left: 20px;
      }
    }
  }
  .pay-section{
    text-align: center;
    .channel-select{
      margin-top: 100px;
    }
    .mock-actions{
      margin-top: 80px;
      p { color: #666; margin-bottom: 30px; }
      .el-button { width: 260px; height: 64px; font-size: 24px; }
    }
    .payContinue{
      width: 95%;
      height: 123px;
      margin-top: 120px;
      font-size: 60px;
      margin-left: 40px;
    }
  }
}

</style>
