<template>
  <a-card :bordered="false">
    <!-- 查询区域 -->
    <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
        </a-row>
      </a-form>
    </div>
    <!-- 查询区域-END -->

    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" @click="handleExportXls('区域入侵配置')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl" @change="handleImportExcel">
        <a-button type="primary" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <j-super-query :fieldList="superFieldList" ref="superQueryModal" @handleSuperQuery="handleSuperQuery"></j-super-query>
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel"><a-icon type="delete"/>删除</a-menu-item>
        </a-menu>
        <a-button style="margin-left: 8px"> 批量操作 <a-icon type="down" /></a-button>
      </a-dropdown>
    </div>

    <!-- table区域-begin -->
    <div>
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a style="font-weight: 600">{{ selectedRowKeys.length }}</a>项
        <a style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>

      <a-table
        ref="table"
        size="middle"
        :scroll="{x:true}"
        bordered
        rowKey="id"
        :columns="columns"
        :dataSource="dataSource"
        :pagination="ipagination"
        :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}"
        class="j-table-force-nowrap"
        @change="handleTableChange">

        <template slot="htmlSlot" slot-scope="text">
          <div v-html="text"></div>
        </template>
        <template slot="imgSlot" slot-scope="text,record">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无图片</span>
          <img v-else :src="getImgView(text)" :preview="record.id" height="25px" alt="" style="max-width:80px;font-size: 12px;font-style: italic;"/>
        </template>
        <template slot="fileSlot" slot-scope="text">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无文件</span>
          <a-button
            v-else
            :ghost="true"
            type="primary"
            icon="download"
            size="small"
            @click="downloadFile(text)">
            下载
          </a-button>
        </template>

        <span slot="action" slot-scope="text, record">
          <a @click="handleEdit(record)">编辑</a>

          <a-divider type="vertical" />
          <a-dropdown>
            <a class="ant-dropdown-link">更多 <a-icon type="down" /></a>
            <a-menu slot="overlay">
              <a-menu-item>
                <a @click="handleDetail(record)">详情</a>
              </a-menu-item>
              <a-menu-item>
                <a-popconfirm title="确定删除吗?" @confirm="() => handleDelete(record.id)">
                  <a>删除</a>
                </a-popconfirm>
              </a-menu-item>
            </a-menu>
          </a-dropdown>
        </span>

      </a-table>
    </div>

    <tab-video-util-modal ref="modalForm" @ok="modalFormOk"></tab-video-util-modal>
  </a-card>
</template>

<script>

  import '@/assets/less/TableExpand.less'
  import { mixinDevice } from '@/utils/mixin'
  import { JeecgListMixin } from '@/mixins/JeecgListMixin'
  import TabVideoUtilModal from './modules/TabVideoUtilModal'

  export default {
    name: 'TabVideoUtilList',
    mixins:[JeecgListMixin, mixinDevice],
    components: {
      TabVideoUtilModal
    },
    data () {
      return {
        description: '区域入侵配置管理页面',
        // 表头
        columns: [
          {
            title: '#',
            dataIndex: '',
            key:'rowIndex',
            width:60,
            align:"center",
            customRender:function (t,r,index) {
              return parseInt(index)+1;
            }
          },
          {
            title:'原始尺寸',
            align:"center",
            dataIndex: 'videoStart'
          },
          {
            title:'原始X坐标',
            align:"center",
            dataIndex: 'videoStartx'
          },
          {
            title:'原始y坐标',
            align:"center",
            dataIndex: 'videoStarty'
          },
          {
            title:'结束坐标x',
            align:"center",
            dataIndex: 'videoEndx'
          },
          {
            title:'结束坐标y',
            align:"center",
            dataIndex: 'videoEndy'
          },
          {
            title:'其他内容',
            align:"center",
            dataIndex: 'videoJson'
          },
          {
            title:'实际尺寸',
            align:"center",
            dataIndex: 'canvasStart'
          },
          {
            title:'实际X坐标',
            align:"center",
            dataIndex: 'canvasStartx'
          },
          {
            title:'实际y坐标',
            align:"center",
            dataIndex: 'canvasStarty'
          },
          {
            title:'实际宽度',
            align:"center",
            dataIndex: 'canvasWidth'
          },
          {
            title:'实际高度',
            align:"center",
            dataIndex: 'canvasHeight'
          },
          {
            title:'其他内容',
            align:"center",
            dataIndex: 'canvasJson'
          },
          {
            title:'remerk',
            align:"center",
            dataIndex: 'remerk'
          },
          {
            title:'备注',
            align:"center",
            dataIndex: 'spareOne'
          },
          {
            title:'视频id',
            align:"center",
            dataIndex: 'videoId'
          },
          {
            title:'视频名称',
            align:"center",
            dataIndex: 'videoName'
          },
          {
            title:'备注2',
            align:"center",
            dataIndex: 'spareTwo'
          },
          {
            title:'备注3',
            align:"center",
            dataIndex: 'spateThree'
          },
          {
            title: '操作',
            dataIndex: 'action',
            align:"center",
            fixed:"right",
            width:147,
            scopedSlots: { customRender: 'action' }
          }
        ],
        url: {
          list: "/video/tabVideoUtil/list",
          delete: "/video/tabVideoUtil/delete",
          deleteBatch: "/video/tabVideoUtil/deleteBatch",
          exportXlsUrl: "/video/tabVideoUtil/exportXls",
          importExcelUrl: "video/tabVideoUtil/importExcel",
          
        },
        dictOptions:{},
        superFieldList:[],
      }
    },
    created() {
    this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function(){
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },
    },
    methods: {
      initDictConfig(){
      },
      getSuperFieldList(){
        let fieldList=[];
        fieldList.push({type:'string',value:'videoStart',text:'原始尺寸',dictCode:''})
        fieldList.push({type:'string',value:'videoStartx',text:'原始X坐标',dictCode:''})
        fieldList.push({type:'string',value:'videoStarty',text:'原始y坐标',dictCode:''})
        fieldList.push({type:'string',value:'videoEndx',text:'结束坐标x',dictCode:''})
        fieldList.push({type:'string',value:'videoEndy',text:'结束坐标y',dictCode:''})
        fieldList.push({type:'string',value:'videoJson',text:'其他内容',dictCode:''})
        fieldList.push({type:'string',value:'canvasStart',text:'实际尺寸',dictCode:''})
        fieldList.push({type:'string',value:'canvasStartx',text:'实际X坐标',dictCode:''})
        fieldList.push({type:'string',value:'canvasStarty',text:'实际y坐标',dictCode:''})
        fieldList.push({type:'string',value:'canvasWidth',text:'实际宽度',dictCode:''})
        fieldList.push({type:'string',value:'canvasHeight',text:'实际高度',dictCode:''})
        fieldList.push({type:'string',value:'canvasJson',text:'其他内容',dictCode:''})
        fieldList.push({type:'string',value:'remerk',text:'remerk',dictCode:''})
        fieldList.push({type:'string',value:'spareOne',text:'备注',dictCode:''})
        fieldList.push({type:'string',value:'videoId',text:'视频id',dictCode:''})
        fieldList.push({type:'string',value:'videoName',text:'视频名称',dictCode:''})
        fieldList.push({type:'string',value:'spareTwo',text:'备注2',dictCode:''})
        fieldList.push({type:'string',value:'spateThree',text:'备注3',dictCode:''})
        this.superFieldList = fieldList
      }
    }
  }
</script>
<style scoped>
  @import '~@assets/less/common.less';
</style>