<template>
  <div class="footer">
    <div class="footer-sec">
      <ul class="footer-links">
        <li><router-link to="/index">星演票务</router-link><span class="line">|</span></li>
        <li><span>v5 可靠交易链路</span><span class="line">|</span></li>
        <li><span>工程化演示系统</span></li>
      </ul>
<!--      <div class="footer-ft">-->
<!--        <router-link to="/index" class="link">-->
<!--          <img :src="logo2" alt="">-->
<!--        </router-link>-->
<!--        <div class="code">-->
<!--          <img :src="code" alt="">-->
<!--          <span>APP下载</span>-->
<!--        </div>-->
<!--      </div>-->
      <div class="footer-rt">
<!--        <el-button-->
<!--            type="primary"-->
<!--            style="width:100%;"-->
<!--            class="elBtn"-->
<!--        ><span>在线客服</span>-->
<!--        </el-button>-->
        <div class="footer-details-info">
          <ul class="footer-links">
            <li ><a href="https://beian.miit.gov.cn/" target="_blank">京ICP备2024068560号-2</a><span class="line">|</span></li>
            <li ><a href="https://beian.mps.gov.cn/#/query/webSearch" target="_blank">京公网安备11011402054237号</a></li>
          </ul>

        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped lang="scss">
.footer {
  background-color: #fff;
  width: 100%;
  padding: 30px 0 20px;
  border-top: 1px solid #eee;

  .footer-sec {
    width: 1200px;
    margin: 0 auto;

    .footer-links {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0;
      text-align: center;
      list-style: none;
      font-size: 14px;
      color: #666;

      li {
        a {
          color: #666;
          text-decoration: none;
          transition: color 0.2s;

          &:hover {
            color: #ff371d;
          }
        }

        &:last-child {
          .line {
            display: none !important;
          }
        }

        .line {
          padding: 0 20px;
          color: #ddd;
        }
      }
    }

    .footer-rt {
      margin-top: 16px;

      .footer-details-info {
        width: 100%;
        text-align: center;

        .footer-links {
          padding: 0;
          font-size: 13px;
          color: #999;

          li a {
            color: #999;

            &:hover {
              color: #ff371d;
            }
          }

          .line {
            padding: 0 16px;
            color: #ddd;
          }
        }
      }
    }
  }
}

.btn {
  background-color: rgba(255, 55, 29, 0.85);
  background-image: -webkit-gradient(linear, left top, right top, from(#ff4aae), to(rgba(255, 55, 29, 0.85)));
  background-image: linear-gradient(90deg, #ff4aae, rgba(255, 55, 29, 0.85));
  border-color: rgba(255, 55, 29, 0.85);
  border-radius: 3px;
  font-size: 20px;
  height: 42px;
  line-height: 42px;
  outline: none;
  color: #fff;
  width: 100%;
  cursor: pointer;
}
</style>
