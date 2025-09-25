<template>
  <a-card class="contablelist" :bordered="false">
    <!-- 查询区域 -->
    <!-- <div class="table-page-search-wrapper">
      <a-form layout="inline" @keyup.enter.native="searchQuery">
        <a-row :gutter="24">
        </a-row>
      </a-form>
    </div> -->
    <!-- 查询区域-END -->

 <div class="contable">
    <!-- 操作按钮区域 -->
    <div class="table-operator">
      <a-button @click="handleAdd" type="primary" class="xz" icon="plus">新增</a-button>
      <a-button type="primary" icon="download" class="dc" @click="handleExportXls('模型绑定')">导出</a-button>
      <a-upload name="file" :showUploadList="false" :multiple="false" :headers="tokenHeader" :action="importExcelUrl"
        @change="handleImportExcel">
        <a-button type="primary" class="dr" icon="import">导入</a-button>
      </a-upload>
      <!-- 高级查询区域 -->
      <a-dropdown v-if="selectedRowKeys.length > 0">
        <a-menu slot="overlay">
          <a-menu-item key="1" @click="batchDel">
            <a-icon type="delete" />删除
          </a-menu-item>
        </a-menu>
        <a-button style="margin-left: 8px"> 批量操作
          <a-icon type="down" />
        </a-button>
      </a-dropdown>
    </div>

    <!-- table区域-begin -->
    <div class="datagrid-view">
      <div class="ant-alert ant-alert-info" style="margin-bottom: 16px;">
        <i class="anticon anticon-info-circle ant-alert-icon"></i> 已选择 <a
          style="font-weight: 600">{{ selectedRowKeys.length }}</a>项
        <a style="margin-left: 24px" @click="onClearSelected">清空</a>
      </div>

      <a-table ref="table" size="middle" :scroll="{x:true}" bordered rowKey="id" :columns="columns"
        :dataSource="dataSource" :pagination="ipagination" :loading="loading"
        :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange}" class="j-table-force-nowrap"
        @change="handleTableChange">

        <template slot="htmlSlot" slot-scope="text">
          <div v-html="text"></div>
        </template>
        <template slot="imgSlot" slot-scope="text,record">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无图片</span>
          <img v-else :src="getImgView(text)" :preview="record.id" height="25px" alt=""
            style="max-width:80px;font-size: 12px;font-style: italic;" />
        </template>
        <template slot="fileSlot" slot-scope="text">
          <span v-if="!text" style="font-size: 12px;font-style: italic;">无文件</span>
          <a-button v-else :ghost="true" type="primary" icon="download" size="small" @click="downloadFile(text)">
            下载
          </a-button>
        </template>

        <span slot="action" slot-scope="text, record">
		  <a @click="handleEdit(record)">编辑</a>

		  <a-divider type="vertical" />
		  <a @click="handleIdentify(record)">AI识别</a>
		  <a-divider type="vertical" v-if="record.spaceOne==='1'" />
		  <a v-if="record.spaceOne==='1'" @click="handleIdentifyClose(record)">视频识别结束</a>
		  
		  <a-divider type="vertical" v-if="record.spaceOne==='1'" />
		  <a  v-if="record.spaceOne==='1'" @click="handleOpenVideo(record)">视频区域报警配置</a>
		  <a-divider type="vertical" />
		  <a-dropdown>
			<a class="ant-dropdown-link">更多
			  <a-icon type="down" />
			</a>
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

    <tab-ai-model-bund-modal class="contc" :width="1200" ref="modalForm" @ok="modalFormOk"></tab-ai-model-bund-modal>
    </div>
  </a-card>
</template>

<script>
  import {
    httpAction,
    getAction,getFileAccessHttpUrl
  } from '@/api/manage'
  import '@/assets/less/TableExpand.less'
  import {
    mixinDevice
  } from '@/utils/mixin'
  import {
    JeecgListMixin
  } from '@/mixins/JeecgListMixin'
  import TabAiModelBundModal from './modules/TabAiModelBundModal'

  export default {
    name: 'TabAiModelBundList',
    mixins: [JeecgListMixin, mixinDevice],
    components: {
      TabAiModelBundModal
    },
    data() {
      return {
        description: '模型绑定管理页面',
        // 表头
        columns: [{
            title: '#',
            dataIndex: '',
            key: 'rowIndex',
            width: 60,
            align: "center",
            customRender: function(t, r, index) {
              return parseInt(index) + 1;
            }
          },
          {
            title: '识别名称',
            align: "center",
            dataIndex: 'spaceTwo'
          },
          {
            title: '模型名称',
            align: "center",
            dataIndex: 'modelName_dictText'
          }, {
            title: '识别类型',
            align: "center",
            dataIndex: 'spaceOne_dictText'
          },
          {
            title: '图片|视频地址',
            align: "center",
            dataIndex: 'sendUrl'
          },
          {
            title: '图片显示',
            align: "center",
            dataIndex: 'saveUrl',
            scopedSlots: {
              customRender: 'imgSlot'
            }
          },
          {
            title: '备注',
            align: "center",
            dataIndex: 'remake'
          },
          {
            title: '操作',
            dataIndex: 'action',
            align: "center",
            fixed: "right",
            width: 200,
            scopedSlots: {
              customRender: 'action'
            }
          }
        ],
        url: {
          list: "/tab/tabAiModelBund/listVideo", 
          delete: "/tab/tabAiModelBund/delete",
          deleteBatch: "/tab/tabAiModelBund/deleteBatch",
          exportXlsUrl: "/tab/tabAiModelBund/exportXls",
          importExcelUrl: "tab/tabAiModelBund/importExcel",
          identifyUrl: "/tab/tabAiHistory/addIdentify",
          identifyCloseUrl: "/tab/tabAiHistory/addIdentifyClose"
        },
        dictOptions: {},
        superFieldList: [],
      }
    },
    created() {
      this.getSuperFieldList();
    },
    computed: {
      importExcelUrl: function() {
        return `${window._CONFIG['domianURL']}/${this.url.importExcelUrl}`;
      },
    },
    methods: {
      initDictConfig() {},
      getSuperFieldList() {
        let fieldList = [];
        fieldList.push({
          type: 'string',
          value: 'modelName',
          text: '模型名称',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'sendUrl',
          text: '输入图片地址',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'saveUrl',
          text: '保存图片地址',
          dictCode: ''
        })
        fieldList.push({
          type: 'string',
          value: 'remake',
          text: '备注',
          dictCode: ''
        })
        this.superFieldList = fieldList
      },
      handleIdentify(info) {
        console.log("info", this.url);
        let that = this;
        this.$confirm({
          title: "确认识别吗",
          content: "手动触发图片识别只会生成一次结果! 但视频会识别到结束",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += that.url.identifyUrl;
            method = 'post';

            httpAction(httpurl, info, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
            }).finally(() => {
              that.confirmLoading = false;
            })

          }
        });
      },
      handleIdentifyClose(info) {
        let that = this;
        this.$confirm({
          title: "确认结束视频结束吗",
          content: "结束视频识别结果输出!",
          onOk: function() {
            let httpurl = '';
            let method = '';
            //  debugger;
            httpurl += that.url.identifyCloseUrl;
            method = 'post';

            httpAction(httpurl, info, method).then((res) => {
              if (res.success) {
                that.$message.success(res.message);
                that.$emit('ok');
              } else {
                that.$message.warning(res.message);
              }
            }).finally(() => {
              that.confirmLoading = false;
            })

          }
        });
      },
      handleOpenVideo(info){
       this.$router.push('livecanvas/AddressList');
      }
    }
  }
</script>
<style src="@assets/zwyStyle/css/main.css"></style>
<style scoped>
  @import '~@assets/less/common.less';
  /* /deep/ .ant-table-scroll{height: calc(100vh - 337px);} */
.datagrid-view {
	height:76vh!important;
}
/deep/.ant-table {
	height:calc(74vh - 91px) !important;overflow: auto;
}
</style>