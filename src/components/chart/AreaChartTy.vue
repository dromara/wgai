<template>
  <div :style="{ padding: '0' ,height:'100%'}">
    <a-col>
                <h4 :style="{ marginBottom: '20px' }">
                  <img src="~@assets/zwyStyle/img/bg-10.png" width="15" height="15" alt="" /><span
                    style="margin-left: 6px"
                    >{{ title }}</span
                  >
                </h4></a-col
              >
    <!-- <h4 :style="{ marginBottom: '20px' }">{{ title }}</h4> -->

    <div style="height: calc(100% - 41px)">
      <v-chart
        ref="chart"
        :forceFit="true"
        :padding="padding"
        :height="computedHeight"
        style="height: 100%;"
        :data="dataSource"
        :scale="scale"
      >
        <v-tooltip :shared="false" />
        <v-axis />
        <v-line position="x*y" :size="lineSize" :color="lineColor" />
        <v-area position="x*y" :color="color" />
      </v-chart>
    </div>
  </div>
</template>

<script>
import { triggerWindowResizeEvent } from '@/utils/util'

export default {
  name: 'AreaChartTy',
  props: {
    // 图表数据
    dataSource: {
      type: Array,
      required: true,
    },
    // 图表标题
    title: {
      type: String,
      default: '',
    },
    // x 轴别名
    x: {
      type: String,
      default: 'x',
    },
    // y 轴别名
    y: {
      type: String,
      default: 'y',
    },
    // Y轴最小值
    min: {
      type: Number,
      default: 0,
    },
    // Y轴最大值
    max: {
      type: Number,
      default: null,
    },
    // 图表高度
    height: {
      type: [String, Number],
      default: 254,
    },
    // 线的粗细
    lineSize: {
      type: Number,
      default: 2,
    },
    // 面积的颜色
    color: {
      type: String,
      default: '',
    },
    // 线的颜色
    lineColor: {
      type: String,
      default: '',
    },
  },
  data() {
    return {
      padding: { top: 18, right: 30, bottom: 55, left: 50 },
      parentHeight: 0, // 用于存储父组件高度
    }
  },
  computed: {
    scale() {
      return [
        { dataKey: 'x', title: this.x, alias: this.x },
        { dataKey: 'y', title: this.y, alias: this.y, min: this.min, max: this.max },
      ]
    },
    computedHeight() {
      // 如果传入的是字符串且包含百分比，则基于父元素高度计算
      if (typeof this.height === 'string' && this.height.includes('%')) {
        const percentage = parseFloat(this.height) / 100
        return this.parentHeight * percentage
      }

      // 否则直接返回高度值（数字或转换后的数字）
      console.log('computedHeight', Number(this.height))
      return Number(this.height)
    },
  },
  mounted() {
    this.updateParentHeight()
    triggerWindowResizeEvent()
    // 添加窗口 resize 事件监听
    window.addEventListener('resize', this.updateParentHeight)
  },
  beforeDestroy() {
    // 移除事件监听
    window.removeEventListener('resize', this.updateParentHeight)
  },
  methods: {
    updateParentHeight() {
      // 获取父元素的高度
      if (this.$el && this.$el.parentElement) {
        this.parentHeight = this.$el.parentElement.clientHeight - 41
      }
    },
  },
}
</script>

<style lang="less" scoped>
@import 'chart';
</style>
