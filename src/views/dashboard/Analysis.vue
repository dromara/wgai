<template>
  <div style="width: 100%; height: 100%; overflow: hidden">
    <a-skeleton style="height: 100%" class="conbody" active :loading="loading" :paragraph="{ rows: 17 }">
      <a-card style="height: 100%">
        <!-- Redis 信息实时监控 -->
        <a-col :span="8" style="height: 100%">
          <!-- 接入数据 -->
          <a-row class="conlist in">
            <a-col>
              <h4>
                <img src="~@assets/zwyStyle/img/bg-10.png" width="15" height="15" alt="" /><span
                  style="margin-left: 6px"
                  >接入数据</span
                >
              </h4>
            </a-col>

            <a-row class="base-box">
              <a-col :span="11" class="box-col box-col-one">
                <a-col class="box-col-left" style=""> </a-col>
                <a-col class="box-col-right">
                  <a-row><span>接入摄像头数</span></a-row>
                  <a-row><span class="box-col-right-bf">{{cameraNumber}}</span> 个</a-row>
                </a-col>
              </a-col>
              <a-col :span="11" class="box-col box-col-two">
                <a-col class="box-col-left change-bg"></a-col>
                <a-col class="box-col-right">
                  <a-row><span>接入模型数</span></a-row>
                  <a-row><span class="box-col-right-bf change-color">{{modelNumber}}</span> 个</a-row>
                </a-col></a-col
              >
            </a-row>
          </a-row>
          <!-- 内存实时占用情况 -->
          <a-row style="height: calc(100% - 175px)">
            <a-row class="conlist chart">
              <area-chart-ty v-bind="memory" />
            </a-row>
            <a-row class="conlist chart">
              <area-chart-ty v-bind="key" />
            </a-row>
          </a-row>
        </a-col>

        <a-col :span="16" style="height: 100%">
          <a-row class="conlist-content right">
            <!-- 模型识别率 -->
            <a-col class="conlist top-table" :span="10" style="width: 40%">
              <a-col>
                <h4>
                  <img src="~@assets/zwyStyle/img/bg-10.png" width="15" height="15" alt="" /><span
                    style="margin-left: 6px"
                    >模型识别率</span
                  >
                </h4>
              </a-col>
              <a @click="handleClickUpdate" class="messagegx">立即更新</a>
              <a-alert type="info" :showIcon="true" class="messagediv">
                <div slot="message">
                  上次更新时间：<span class="span">{{ this.time }}</span>
                  <a-divider type="vertical" />
                </div>
              </a-alert>

              <a-table
                class="contable"
                rowKey="id"
                size="middle"
                :columns="modelColumns"
                :dataSource="modelDataSource"
                :pagination="false"
                :loading="tableLoading"
                style="margin-top: 10px; max-height: 70%; overflow-y: scroll"
              >
               <template slot="param" slot-scope="text, record">
                 <a-tag :color="getRandomColor()">{{ text }}</a-tag>
               </template>

                <!-- <template slot="text" slot-scope="text, record">
                  {{ textInfoSystem[record.param].text }}
                </template> -->

                
              </a-table>
            </a-col>
            <!-- 服务器CPU使用率 -->
            <a-col class="conlist top-table" :span="14" style="width: calc(100% - 40% - 10px); margin-left: 10px">
              <a-col>
                <h4>
                  <img src="~@assets/zwyStyle/img/bg-10.png" width="15" height="15" alt="" /><span
                    style="margin-left: 6px"
                    >服务器CPU使用率</span
                  >
                </h4></a-col
              >
              <a @click="handleClickUpdate" class="messagegx">立即更新</a>
              <a-alert type="info" :showIcon="true" class="messagediv">
                <div slot="message">
                  上次更新时间：<span class="span">{{ this.time }}</span>
                  <a-divider type="vertical" />
                </div>
              </a-alert>

              <a-table
                class="contable"
                rowKey="id"
                size="middle"
                :columns="columnsSystem"
                :dataSource="dataSourceSystem"
                :pagination="false"
                :loading="tableLoading"
                style="margin-top: 10px; max-height: 70%; overflow-y: scroll"
              >
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
           <!-- JVM 详细信息 -->
          <a-row class="conlist on">
            <a-col>
                <h4>
                  <img src="~@assets/zwyStyle/img/bg-10.png" width="15" height="15" alt="" /><span
                    style="margin-left: 6px"
                    >JVM 详细信息</span
                  >
                </h4></a-col
              >
            <a @click="handleClickUpdate" class="messagegx">立即更新</a>
            <a-alert type="info" :showIcon="true" class="messagediv on">
              <div slot="message">
                上次更新时间：<span class="span">{{ this.time }}</span>
                <a-divider type="vertical" />
              </div>
            </a-alert>

            <a-table
              class="contable"
              rowKey="id"
              size="middle"
              :columns="columns"
              :dataSource="dataSource"
              :pagination="false"
              :loading="tableLoading"
              style="margin-top: 10px; max-height: 75%; overflow-y: scroll"
            >
              <template slot="param" slot-scope="text, record">
                <a-tag :color="textInfo[record.param].color">{{ text }}</a-tag>
              </template>

              <template slot="text" slot-scope="text, record">
                {{ textInfo[record.param].text }}
              </template>

              <template slot="value" slot-scope="text, record"> {{ text }} {{ textInfo[record.param].unit }} </template>
            </a-table>
          </a-row>
        </a-col>
      </a-card>
    </a-skeleton>
  </div>
</template>
<script>
import moment from 'moment'
import { getAction } from '@/api/manage'
import AreaChartTy from '@/components/chart/AreaChartTy'

export default {
  name: 'RedisInfo',
  components: {
    AreaChartTy,
  },
  data() {
    return {
      cameraNumber:0,
      modelNumber:0,
      dataSourceSystem: [],
      modelDataSource:[{
        // id:'model', 
        param:'smoking',
        text:'75.21%',
      },{
        // id:'model', 
        param:'safety  hat',
        text:'69.56%',
      },
    {
        // id:'model', 
        param:'falls  down',
        text:'81.23%',
      },
    {
        // id:'model', 
        param:'open  windows',
        text:'69.56%',
      },{
        // id:'model', 
        param:'open  door',
        text:'81.23%',
      }],
      modelColumns: [   {
          title: '模型名称',
          width: '50%',
          align: 'center',
          dataIndex: 'param',
          scopedSlots: { customRender: 'param' },
        },
        {
          title: '识别率',
          width: '50%',
          align: 'center',
          dataIndex: 'text',
          scopedSlots: { customRender: 'text' },
        },],
      columnsSystem: [
        {
          title: '参数',
          width: '30%',
          dataIndex: 'param',
          align: 'center',
          scopedSlots: { customRender: 'param' },
        },
        {
          title: '描述',
          width: '40%',
          align: 'center',
          dataIndex: 'text',
          scopedSlots: { customRender: 'text' },
        },
        {
          title: '当前值',
          width: '30%',
          align: 'center',
          dataIndex: 'value',
          scopedSlots: { customRender: 'value' },
        },
      ],
      textInfoSystem: {
        'system.cpu.count': { color: 'green', text: 'CPU 数量', unit: '核' },
        'system.cpu.usage': { color: 'green', text: '系统 CPU 使用率', unit: '%' },
        'process.start.time': { color: 'purple', text: '应用启动时间点', unit: '' },
        'process.uptime': { color: 'purple', text: '应用已运行时间', unit: '秒' },
        'process.cpu.usage': { color: 'purple', text: '当前应用 CPU 使用率', unit: '%' },
      },
      textInfoModel: {
        'smoking': { color: 'green', unit: '%' },
        'safety  hat': { color: 'pink',  unit: '%' },
        'falls  down': { color: 'purple',  unit: '%' },
        'open windows':{ color: 'blue',  unit: '%' },
        'open  door': { color: 'orange',  unit: '%' },
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
        height: '100%',
        min: 0,
        max: 100,
        color: '#03aba71a',
        lineSize: 8,
        lineColor: '#84CCC9',
      },
      // 内存实时占用情况
      memory: {
        title: 'Redis 内存实时占用情况（KB）',
        dataSource: [],
        y: '内存（KB）',
        min: 0,
        max: 3000,
        height: '100%',
        lineSize: 8,
        color: '#2a7fff1a', // 自定义面积的颜色
        lineColor: '#83B6FF', // 自定义线的颜色
      },
      redisInfo: [],
      columns: [
        {
          title: '参数',
          width: '30%',
          align: 'center',
          dataIndex: 'param',
          scopedSlots: { customRender: 'param' },
        },
        {
          title: '描述',
          width: '40%',
          align: 'center',
          dataIndex: 'text',
          scopedSlots: { customRender: 'text' },
        },
        {
          title: '当前值',
          width: '30%',
          align: 'center',
          dataIndex: 'value',
          scopedSlots: { customRender: 'value' },
        },
      ],
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
        'jvm.gc.pause.totalTime': { color: 'blue', text: '系统启动以来GC 总耗时', unit: '秒' },
      },
      // 当一条记录中需要取出多条数据的时候需要配置该字段
      moreInfo: {
        'jvm.gc.pause': ['.count', '.totalTime'],
      },
      url: {
        keysSize: '/sys/actuator/redis/keysSize',
        memoryInfo: '/sys/actuator/redis/memoryInfo',
        info: '/sys/actuator/redis/info',
      },
      path: '/monitor/redis/info',
    }
  },
  mounted() {
    this. getCameraList();
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
     getRandomColor() {
        const colors = ['magenta','red','volcano','orange','gold','lime','green','cyan','blue','geekblue','purple'];
        return colors[Math.floor(Math.random() * colors.length)];
      },
    handleClickUpdate() {
      this.loadTomcatInfo()
    },
    getCameraList(){
      let that=this;
      getAction("/tab/tabAiModel/getIndexInfo", {}).then((res) => {
        console.log("获取首页数据",res)
        let result=res.result;
        that.cameraNumber=result.cameraNumber;
        that.modelNumber=result.modelNmber;
        that.modelDataSource=[];
        let tabAiModelList=result.tabAiModel;
        for (var i = 0; i < tabAiModelList.length; i++) {
            let modelDataSourceList={};
              modelDataSourceList.param=tabAiModelList[i].aiName;
              modelDataSourceList.text=(tabAiModelList[i].spareFour||90)+"%";
            
            that.modelDataSource.push(modelDataSourceList);
        }
      })
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
        getAction('actuator/metrics/jvm.gc.pause'),
      ])
        .then((res) => {
          let info = []
          res.forEach((value, id) => {
            let more = this.moreInfo[value.name]
            if (!(more instanceof Array)) {
              more = ['']
            }
            more.forEach((item, idx) => {
              let param = value.name + item
              let val = value.measurements[idx].value

              if (
                param === 'jvm.memory.max' ||
                param === 'jvm.memory.committed' ||
                param === 'jvm.memory.used' ||
                param === 'jvm.buffer.memory.used' ||
                param === 'jvm.gc.memory.allocated' ||
                param === 'jvm.gc.memory.promoted' ||
                param === 'jvm.gc.max.data.size' ||
                param === 'jvm.gc.live.data.size'
              ) {
                val = this.convert(val, Number)
              }
              info.push({ id: param + id, param, text: 'false value', value: val })
            })
          })
          this.dataSource = info
        })
        .catch((e) => {
          console.error(e)
          this.$message.error('获取JVM信息失败')
        })
        .finally(() => {
          this.loading = false
          this.tableLoading = false
        })

      Promise.all([
        getAction('actuator/metrics/system.cpu.count'),
        getAction('actuator/metrics/system.cpu.usage'),
        getAction('actuator/metrics/process.start.time'),
        getAction('actuator/metrics/process.uptime'),
        getAction('actuator/metrics/process.cpu.usage'),
      ])
        .then((res) => {
          let info = []
          res.forEach((value, id) => {
            let more = this.moreInfo[value.name]
            if (!(more instanceof Array)) {
              more = ['']
            }
            more.forEach((item, idx) => {
              let param = value.name + item
              let measurements = value.measurements[idx]
              let val = value.measurements[idx].value

              if (param === 'system.cpu.usage' || param === 'process.cpu.usage') {
                val = (val * 100).toFixed(3)
              }
              if (param === 'process.start.time') {
                val = this.convert(val, Date)
              }
              info.push({ id: param + id, param, text: 'false value', value: val })
            })
          })

          this.dataSourceSystem = info
        })
        .catch((e) => {
          console.error(e)
          this.$message.error('获取服务器信息失败')
        })
        .finally(() => {
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
      Promise.all([getAction(this.url.keysSize), getAction(this.url.memoryInfo)])
        .then((res) => {
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
        })
        .catch((e) => {
          console.error(e)
          this.closeTimer()
          this.$message.error('获取 Redis 信息失败')
        })
        .finally(() => {
          this.loading = false
        })
    },

    // 获取一组数据中最大和最小的值
    getMaxAndMin(dataSource, field) {
      let maxValue = null,
        minValue = null
      dataSource.forEach((item) => {
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
  },
}
</script>
<style>
.conbody {
  background: none !important;
  border: none !important;
}
.conbody .ant-card-body {
  height: 100%;
  padding: 0;
}
.conbody h4 {
  color: #0364ff;
}
.conlist {
  margin: 5px;
  background: linear-gradient(to top, #ffffff, #eef6ff) !important;
  box-shadow: 0 0 10px rgba(3, 100, 255, 0.1);
  border-radius: 10px;
  width: 100%;
  padding: 10px !important;
  height: 365px;
}
.conlist-content {
  margin: 5px 5px 10px;
  width: calc(100% - 10px) !important;
  height: 42.9%;
  /* margin-bottom: 10px; */
}
.conlist.on {
  width: calc(100% - 10px) !important;
  /* height: 54.58%; */
  height: calc(100% - 42.9% - 15px);
}
.conlist.in {
  height: 170px;
  width: calc(100% - 10px) !important;
}
.conlist-content.right {
  width: calc(100% - 10px) !important;
}
.conlist-content.right .top-table {
  width: unset;
  margin: 0;
  height: 100%;
}
.conlist.chart {
  margin-bottom: 10px;
  width: calc(100% - 10px) !important;
  height: calc(50% - 10px) !important;
}
.messagediv {
  font-size: 12px;
  background: url(~@assets/zwyStyle/img/bg-03.png) no-repeat;
  background-size: 100% 100%;
  border: none !important;
  color: #51565c !important;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
}
.messagediv.on {
  background: url(~@assets/zwyStyle/img/bg-04.png) no-repeat;
  background-size: 100% 100%;
}
.messagegx {
  position: absolute;
  top: 10px;
  right: 10px;
  background: #0364ff;
  color: #fff;
  border-radius: 5px;
  padding: 0 10px;
  font-size: 12px;
  height: 21px;
  line-height: 21px;
  display: block;
}
.messagegx:hover {
  color: #fff;
}
.messagediv .span {
  color: #0364ff;
}
.contable .ant-table-tbody {
  background: #f6f8fb;
}
.contable {
  height: calc(100% - 80px) !important;
}
.base-box {
  margin-top: 19px;
  display: flex;
  width: 100%;
  justify-content: space-between;
}
.box-col {
  display: flex;
  padding: 20px 0;
  border-radius: 8px;
  align-items: center;
  justify-content: space-evenly;
}
.box-col-one {
  background: rgba(253, 191, 1, 0.08);
}
.box-col-two {
  background: rgba(1, 205, 128, 0.08);
}
.box-col-left {
  width: 60px;
  height: 60px;
  background: url(~@assets/zwyStyle/img/bg-11.png) no-repeat;
  background-size: 100% 100%;
}
.box-col-right {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-family: Microsoft YaHei;
  font-weight: 400;
  font-size: 14px;
  color: #51565c;
  line-height: 14px;
  white-space: nowrap;
}
.box-col-left.change-bg {
  margin-top: 4px;
  background: url(~@assets/zwyStyle/img/bg-12.png) no-repeat;
}

.box-col-right-bf {
  font-weight: bold;
  font-size: 30px;
  color: #fdbf01;
  line-height: 28px;
}
.change-color {
  color: #02cc80;
}
</style>
