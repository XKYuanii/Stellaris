<template>
<div class="app-container" v-show="orderNumber !== '' && orderNumber !== null">
  <Header></Header>
  <div class="main">
    <el-icon :size="50" class="iconCircle"><CircleCheck color="rgb(255, 40, 105)" /></el-icon>
    <span class="paySuccess">{{ statusText }}</span>
    <el-button v-if="paymentState === 'PENDING'" :loading="checking" @click="checkPayment">重新检查支付状态</el-button>
   <div class="btn">
     <el-button  class="continueQuery" @click="continueQuery"    >继续逛逛</el-button>
     <el-button   class="orderQuery" @click="orderQuery"    >订单列表</el-button>
   </div>
  </div>
  <Footer></Footer>
</div>
</template>

<script setup name="PaySuccess">
import Header from '@/components/header/index'
import Footer from '@/components/footer/index'
import {computed, ref, onMounted} from 'vue'
import {useRouter} from 'vue-router'
import {payCheckApi} from '@/api/order.js'
import {ElMessage} from 'element-plus'
const router = useRouter();
const orderNumber = ref('');
const payChannelType = ref('1')
const paymentState = ref('PENDING')
const checking = ref(false)
const statusText = computed(() => paymentState.value === 'PAID' ? '支付成功' :
    paymentState.value === 'CANCELLED' ? '订单已取消' :
    paymentState.value === 'REFUNDED' ? '订单已退款' :
    paymentState.value === 'FAILED' ? '支付失败' : '支付状态同步中')

//继续逛逛
const  continueQuery=()=>{
  router.replace({path:'/index'})
}
//查看订单列表
const orderQuery=()=>{
  router.push({path:'/orderManagement/index'})
}

async function checkPayment() {
  if (!orderNumber.value || checking.value) return
  checking.value = true
  try {
    const response = await payCheckApi({
      'orderNumber':orderNumber.value,
      'payChannelType':Number(payChannelType.value)
    })
    if (String(response.code) !== '0' || !response.data) {
      ElMessage.error(response.message || '支付状态查询失败')
      return
    }
    const stateByOrderStatus = {1:'PENDING', 2:'CANCELLED', 3:'PAID', 4:'REFUNDED'}
    paymentState.value = stateByOrderStatus[response.data.orderStatus] || 'FAILED'
    localStorage.setItem('paymentState', paymentState.value)
    if (paymentState.value === 'PAID') ElMessage.success('订单支付状态已确认')
  } finally {
    checking.value = false
  }
}

onMounted(async ()=>{
  orderNumber.value =  localStorage.getItem('orderNumber' )
  payChannelType.value = localStorage.getItem('payChannelType') || '1'
  paymentState.value = localStorage.getItem('paymentState') || 'PENDING'
  if (orderNumber.value != '' && orderNumber.value != null){
    await checkPayment()
  }else {
    router.replace({path:'/'})
  }
})
</script>

<style scoped lang="scss">
.app-container{
  width: 1200px;
  margin: 0 auto;
  overflow: auto;

  .main{
    height: 500px;
    width: 100%;
    //background: red
    margin: 0 auto;
    padding-top: 100px;
    text-align: center;
    position: relative;
    .iconCircle{
      position: absolute;
      margin-left: -60px;
      top: 95px;
      }
    .paySuccess{
        font-size: 30px;
       font-weight: bolder;

    }
    .btn{
      text-align: center;
      margin-top: 30px;
      .continueQuery{
        width: 100px;
        height: 30px;
        border-radius: 50px;
        &:hover{
          color: rgba(255, 55, 29, 0.85);
          border-color:  rgba(255, 55, 29, 0.85);
          background: #ffffff;
        }
      }
      .orderQuery{
        width: 100px;
        height: 30px;
        border-radius: 50px;
        &:hover{
          color: rgba(255, 55, 29, 0.85);
          border-color:  rgba(255, 55, 29, 0.85);
          background: #ffffff;
        }
      }
    }
  }
}
</style>
