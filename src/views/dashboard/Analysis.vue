<template>
  <a-skeleton class="conbody" active :loading="loading" :paragraph="{rows: 17}">
    <a-card>
      <!-- Redis 信息实时监控 -->
      <a-row :gutter="8">
        <a-col class="conlist" :sm="24" :xl="8">
          <area-chart-ty v-bind="memory"/>
        </a-col>
        <a-col class="conlist" :sm="24" :xl="8">
          <area-chart-ty v-bind="key"/>
        </a-col>
        <a-col class="conlist" :sm="24" :xl="8">
            <h4>服务器CPU使用率</h4>
            <a @click="handleClickUpdate" class="messagegx">立即更新</a>
        <a-alert type="info" :showIcon="true" class="messagediv">
          <div slot="message">
            上次更新时间：<span class="span">{{ this.time }}</span>
            <a-divider type="vertical"/>
          </div>
        </a-alert>
        
        <a-table class="contable"
          rowKey="id"
          size="middle"
          :columns="columnsSystem"
          :dataSource="dataSourceSystem"
          :pagination="false"
          :loading="tableLoading"
			:scroll="{ y: 268 }"
          style="margin-top: 10px;">
        
          <template slot="param" slot-scope="text, record">
            <a-tag :color="textInfoSystem[record.param].color">{{ text }}</a-tag>
          </template>
        
          <template slot="text" slot-scope="text, record">
            {{ textInfoSystem[record.param].text }}
          </template>
        
          <template slot="value" slot-scope="text, record">
            {{ text }} {{ textInfoSystem[record.param].unit }}
          </template>
        
        </a-table>
           </a-col>
      </a-row>
        
    <a-row :gutter="8">
        <a-col class="conlist on" :sm="24" :xl="24">
		  <h3>JVM 详细信息</h3>
			 <a @click="handleClickUpdate" class="messagegx">立即更新</a>
		 <a-alert type="info" :showIcon="true" class="messagediv on">
		   <div slot="message">
			 上次更新时间：<span class="span">{{ this.time }}</span>
			 <a-divider type="vertical"/>
		   </div>
		 </a-alert>
		 
		 <a-table class="contable"
		   rowKey="id"
		   size="middle"
		   :columns="columns"
		   :dataSource="dataSource"
		   :pagination="false"
		   :loading="tableLoading"
			:scroll="{ y: 205}"
		   style="margin-top: 20px;">
		 
		   <template slot="param" slot-scope="text, record">
			 <a-tag :color="textInfo[record.param].color">{{ text }}</a-tag>
		   </template>
		 
		   <template slot="text" slot-scope="text, record">
			 {{ textInfo[record.param].text }}
		   </template>
		 
		   <template slot="value" slot-scope="text, record">
			 {{ text }} {{ textInfo[record.param].unit }}
		   </template>
		 
		 </a-table>

      </a-col>
      </a-row>
    </a-card>
  </a-skeleton>
</template>
<script>
  import moment from 'moment'
  import { getAction } from '@/api/manage'
  import AreaChartTy from '@/components/chart/AreaChartTy'

  export default {
    name: 'RedisInfo',
    components: {
      AreaChartTy
    },
    data() {
      return {
           dataSourceSystem: [],
           columnsSystem: [{
             title: '参数',
             width: '30%',
             dataIndex: 'param',
             scopedSlots: { customRender: 'param' }
           }, {
             title: '描述',
             width: '40%',
             dataIndex: 'text',
             scopedSlots: { customRender: 'text' }
           }, {
             title: '当前值',
             width: '30%',
             dataIndex: 'value',
             scopedSlots: { customRender: 'value' }
           }],
           textInfoSystem: {
             'system.cpu.count': { color: 'green', text: 'CPU 数量', unit: '核' },
             'system.cpu.usage': { color: 'green', text: '系统 CPU 使用率', unit: '%' },
             'process.start.time': { color: 'purple', text: '应用启动时间点', unit: '' },
             'process.uptime': { color: 'purple', text: '应用已运行时间', unit: '秒' },
             'process.cpu.usage': { color: 'purple', text: '当前应用 CPU 使用率', unit: '%' }
           },
        loading: true,
        tableLoading: true,
        // 定时器ID
        timer: null,
        // 定时器周期
        millisec: 3000,
        // Key 实时数量
        key: {
          title: 'Redis Key 实时数量（个）',
          dataSource: [],
          y: '数量（个）',
          height: 340,
          min: 0,
          max: 100,
          color: '#FF6987',
          lineSize: 8,
          lineColor: '#DC143C'
        },
        // 内存实时占用情况
        memory: {
          title: 'Redis 内存实时占用情况（KB）',
          dataSource: [],
          y: '内存（KB）',
          min: 0,
          max: 3000,
          height: 340,
          lineSize: 8
        },
        redisInfo: [],
      columns: [{
        title: '参数',
        width: '30%',
        dataIndex: 'param',
        scopedSlots: { customRender: 'param' }
      }, {
        title: '描述',
        width: '40%',
        dataIndex: 'text',
        scopedSlots: { customRender: 'text' }
      }, {
        title: '当前值',
        width: '30%',
        dataIndex: 'value',
        scopedSlots: { customRender: 'value' }
      }],
      dataSource: [],
      // 列表通过 textInfo 渲染出颜色、描述和单位
      textInfo: {
        'jvm.memory.max': { color: 'purple', text: 'JVM 最大内存', unit: 'MB' },
        'jvm.memory.committed': { color: 'purple', text: 'JVM 可用内存', unit: 'MB' },
        'jvm.memory.used': { color: 'purple', text: 'JVM 已用内存', unit: 'MB' },
        'jvm.buffer.memory.used': { color: 'cyan', text: 'JVM 缓冲区已用内存', unit: 'MB' },
        'jvm.buffer.count': { color: 'cyan', text: '当前缓冲区数量', unit: '个' },
        'jvm.threads.daemon': { color: 'green', text: 'JVM 守护线程数量', unit: '个' },
        'jvm.threads.live': { color: 'green', text: 'JVM 当前活跃线程数量', unit: '个' },
        'jvm.threads.peak': { color: 'green', text: 'JVM 峰值线程数量', unit: '个' },
        'jvm.classes.loaded': { color: 'orange', text: 'JVM 已加载 Class 数量', unit: '个' },
        'jvm.classes.unloaded': { color: 'orange', text: 'JVM 未加载 Class 数量', unit: '个' },
        'jvm.gc.memory.allocated': { color: 'pink', text: 'GC 时, 年轻代分配的内存空间', unit: 'MB' },
        'jvm.gc.memory.promoted': { color: 'pink', text: 'GC 时, 老年代分配的内存空间', unit: 'MB' },
        'jvm.gc.max.data.size': { color: 'pink', text: 'GC 时, 老年代的最大内存空间', unit: 'MB' },
        'jvm.gc.live.data.size': { color: 'pink', text: 'FullGC 时, 老年代的内存空间', unit: 'MB' },
        'jvm.gc.pause.count': { color: 'blue', text: '系统启动以来GC 次数', unit: '次' },
        'jvm.gc.pause.totalTime': { color: 'blue', text: '系统启动以来GC 总耗时', unit: '秒' }
      },
      // 当一条记录中需要取出多条数据的时候需要配置该字段
      moreInfo: {
        'jvm.gc.pause': ['.count', '.totalTime']
      },
        url: {
          keysSize: '/sys/actuator/redis/keysSize',
          memoryInfo: '/sys/actuator/redis/memoryInfo',
          info: '/sys/actuator/redis/info'
        },
        path: '/monitor/redis/info'
      }
    },
    mounted() {
        
      this.openTimer()
      this.loadTomcatInfo()
      setTimeout(() => {
        this.loadData()
      }, 1000)
    },
    beforeDestroy() {
      this.closeTimer()
    },
    methods: {
      handleClickUpdate() {
        this.loadTomcatInfo();

      },
      
 loadTomcatInfo() {
        this.tableLoading = true
        this.time = moment().format('YYYY年MM月DD日 HH时mm分ss秒')
        Promise.all([
          getAction('actuator/metrics/jvm.memory.max'),
          getAction('actuator/metrics/jvm.memory.committed'),
          getAction('actuator/metrics/jvm.memory.used'),
          getAction('actuator/metrics/jvm.buffer.memory.used'),
          getAction('actuator/metrics/jvm.buffer.count'),
          getAction('actuator/metrics/jvm.threads.daemon'),
          getAction('actuator/metrics/jvm.threads.live'),
          getAction('actuator/metrics/jvm.threads.peak'),
          getAction('actuator/metrics/jvm.classes.loaded'),
          getAction('actuator/metrics/jvm.classes.unloaded'),
          getAction('actuator/metrics/jvm.gc.memory.allocated'),
          getAction('actuator/metrics/jvm.gc.memory.promoted'),
          getAction('actuator/metrics/jvm.gc.max.data.size'),
          getAction('actuator/metrics/jvm.gc.live.data.size'),
          getAction('actuator/metrics/jvm.gc.pause')
        ]).then((res) => {

          let info = []
          res.forEach((value, id) => {
            let more = this.moreInfo[value.name]
            if (!(more instanceof Array)) {
              more = ['']
            }
            more.forEach((item, idx) => {
              let param = value.name + item
              let val = value.measurements[idx].value

              if (param === 'jvm.memory.max'
                || param === 'jvm.memory.committed'
                || param === 'jvm.memory.used'
                || param === 'jvm.buffer.memory.used'
                || param === 'jvm.gc.memory.allocated'
                || param === 'jvm.gc.memory.promoted'
                || param === 'jvm.gc.max.data.size'
                || param === 'jvm.gc.live.data.size'
              ) {
                val = this.convert(val, Number)
              }
              info.push({ id: param + id, param, text: 'false value', value: val })
            })
          })
          this.dataSource = info


        }).catch((e) => {
          console.error(e)
          this.$message.error('获取JVM信息失败')
        }).finally(() => {
          this.loading = false
          this.tableLoading = false
        })
        
        Promise.all([
          getAction('actuator/metrics/system.cpu.count'),
          getAction('actuator/metrics/system.cpu.usage'),
          getAction('actuator/metrics/process.start.time'),
          getAction('actuator/metrics/process.uptime'),
          getAction('actuator/metrics/process.cpu.usage')
        ]).then((res) => {
          let info = []
          res.forEach((value, id) => {
            let more = this.moreInfo[value.name]
            if (!(more instanceof Array)) {
              more = ['']
            }
            more.forEach((item, idx) => {
              let param = value.name + item
              let val = value.measurements[idx].value
              if (param === 'system.cpu.usage' || param === 'process.cpu.usage') {
                val = this.convert(val, Number)
              }
              if (param === 'process.start.time') {
                val = this.convert(val, Date)
              }
              info.push({ id: param + id, param, text: 'false value', value: val })
            })
          })
          this.dataSourceSystem = info
        }).catch((e) => {
          console.error(e)
          this.$message.error('获取服务器信息失败')
        }).finally(() => {
          this.loading = false
          this.tableLoading = false
        })
        
      },

      convert(value, type) {
        if (type === Number) {
          return Number(value / 1048576).toFixed(3)
        } else if (type === Date) {
          return moment(value * 1000).format('YYYY-MM-DD HH:mm:ss')
        }
        return value
      },
      /** 开启定时器 */
      openTimer() {
        this.loadData()
        this.closeTimer()
        this.timer = setInterval(() => {
        
            this.loadData()
          
        }, this.millisec)
      },

      /** 关闭定时器 */
      closeTimer() {
        if (this.timer) clearInterval(this.timer)
      },

      /** 查询数据 */
      loadData() {
        Promise.all([
          getAction(this.url.keysSize),
          getAction(this.url.memoryInfo)
        ]).then((res) => {
          let time = moment().format('hh:mm:ss')

          let [{ dbSize: currentSize }, memoryInfo] = res
          let currentMemory = memoryInfo.used_memory / 1000

          // push 数据
          this.key.dataSource.push({ x: time, y: currentSize })
          this.memory.dataSource.push({ x: time, y: currentMemory })
          // 最大长度为6
          if (this.key.dataSource.length > 6) {
            this.key.dataSource.splice(0, 1)
            this.memory.dataSource.splice(0, 1)
          }

          // 计算 Key 最大最小值
          let keyPole = this.getMaxAndMin(this.key.dataSource, 'y')
          this.key.max = Math.floor(keyPole[0]) + 10
          this.key.min = Math.floor(keyPole[1]) - 10
          if (this.key.min < 0) this.key.min = 0

          // 计算 Memory 最大最小值
          let memoryPole = this.getMaxAndMin(this.memory.dataSource, 'y')
          this.memory.max = Math.floor(memoryPole[0]) + 100
          this.memory.min = Math.floor(memoryPole[1]) - 100
          if (this.memory.min < 0) this.memory.min = 0

        }).catch((e) => {
          console.error(e)
          this.closeTimer()
          this.$message.error('获取 Redis 信息失败')
        }).finally(() => {
          this.loading = false
        })

      },

      // 获取一组数据中最大和最小的值
      getMaxAndMin(dataSource, field) {
        let maxValue = null, minValue = null
        dataSource.forEach(item => {
          let value = Number.parseInt(item[field])
          // max
          if (maxValue == null) {
            maxValue = value
          } else if (value > maxValue) {
            maxValue = value
          }
          // min
          if (minValue == null) {
            minValue = value
          } else if (value < minValue) {
            minValue = value
          }
        })
        return [maxValue, minValue]
      },


    }
  }
</script>
<style>
	.conbody{background: none!important;border:none!important;}
	.conbody .ant-card-body{padding: 5px;}
	.conbody h4{color:#0364ff;}
	.conlist{margin:5px;background: linear-gradient(to top, #ffffff, #eef6ff) !important;box-shadow:0 0 10px rgba(3,100,255,0.1);border-radius: 10px;width: calc(33.3% - 10px);padding: 10px !important;}
	.conlist.on{width: calc(100% - 10px)!important;}
	.messagediv{font-size: 12px;background: url(~@assets/zwyStyle/img/bg-03.png) no-repeat;background-size: 100% 100%;border: none!important;color: #51565c!important;}
	.messagediv.on{background: url(~@assets/zwyStyle/img/bg-04.png) no-repeat;background-size: 100% 100%;}
	.messagegx{position: absolute; top: 10px;right: 10px;background: #0364ff;color: #fff;border-radius: 5px;padding: 0 10px;font-size: 12px;height: 21px;line-height:21px;display: block;}
	.messagegx:hover{color: #fff;}
	.messagediv .span{color: #0364ff;}
	.contable .ant-table-tbody{background: #f6f8fb;}
</style>
