// pages/preview/preview.js
// 实时预览页面
const app = getApp();

Page({
  data: {
    device: null,
    deviceOnline: false,
    previewUrl: '',
    previewLoading: true,
    previewError: false,
    errorMessage: '',
    isStreaming: false,
    lastUpdateTime: '',
    refreshInterval: 2000  // 2秒刷新一次
  },

  refreshTimer: null,
  startTime: null,

  onLoad: function() {
    const device = app.globalData.boundDevice;
    console.log('预览页加载，绑定设备:', device);
    if (!device) {
      wx.showToast({
        title: '未绑定设备',
        icon: 'none'
      });
      setTimeout(() => wx.navigateBack(), 1500);
      return;
    }

    console.log('设备ID:', device.deviceId);
    this.setData({ device: device });
    this.checkDeviceStatus();
  },

  onShow: function() {
    if (this.data.device && this.data.isStreaming) {
      this.startPreviewRefresh();
    }
  },

  onHide: function() {
    this.stopPreviewRefresh();
  },

  onUnload: function() {
    this.stopPreviewRefresh();
    // 发送停止预览命令
    if (this.data.isStreaming) {
      this.sendStopPreview();
    }
  },

  // 检查设备状态
  checkDeviceStatus: function() {
    wx.cloud.callFunction({
      name: 'getDeviceStatus',
      data: {
        deviceId: this.data.device.deviceId
      }
    }).then(res => {
      if (res.result && res.result.success) {
        const online = res.result.device.online || false;
        this.setData({
          deviceOnline: online,
          previewLoading: false
        });
        
        if (online) {
          // 自动开始预览
          this.startPreview();
        } else {
          this.setData({
            previewError: true,
            errorMessage: '设备离线，无法预览'
          });
        }
      } else {
        this.setData({
          deviceOnline: false,
          previewLoading: false,
          previewError: true,
          errorMessage: '获取设备状态失败'
        });
      }
    }).catch(err => {
      console.error('获取状态失败', err);
      this.setData({
        deviceOnline: false,
        previewLoading: false,
        previewError: true,
        errorMessage: '网络错误'
      });
    });
  },

  // 开始预览
  startPreview: function() {
    if (this.data.isStreaming) return;

    this.setData({
      previewLoading: true,
      previewError: false,
      isStreaming: true
    });

    // 发送开始预览命令
    wx.cloud.callFunction({
      name: 'commandSend',
      data: {
        deviceId: this.data.device.deviceId,
        command: 'start_preview',
        params: {}
      }
    }).then(res => {
      if (res.result && res.result.success) {
        console.log('开始预览命令已发送');
        this.startTime = Date.now();
        // 等待1秒后开始刷新预览图
        setTimeout(() => {
          this.startPreviewRefresh();
        }, 1000);
      } else {
        this.setData({
          previewLoading: false,
          previewError: true,
          isStreaming: false,
          errorMessage: res.result?.message || '启动预览失败'
        });
      }
    }).catch(err => {
      console.error('发送预览命令失败', err);
      this.setData({
        previewLoading: false,
        previewError: true,
        isStreaming: false,
        errorMessage: '发送命令失败'
      });
    });
  },

  // 停止预览
  stopPreview: function() {
    this.stopPreviewRefresh();
    this.sendStopPreview();
    
    this.setData({
      isStreaming: false,
      previewUrl: '',
      previewLoading: false
    });
  },

  // 发送停止预览命令
  sendStopPreview: function() {
    wx.cloud.callFunction({
      name: 'commandSend',
      data: {
        deviceId: this.data.device.deviceId,
        command: 'stop_preview',
        params: {}
      }
    }).then(res => {
      console.log('停止预览命令已发送');
    }).catch(err => {
      console.error('发送停止预览命令失败', err);
    });
  },

  // 开始定期刷新预览图
  startPreviewRefresh: function() {
    this.stopPreviewRefresh();
    
    // 立即获取一次
    this.fetchPreviewImage();
    
    // 定期刷新
    this.refreshTimer = setInterval(() => {
      this.fetchPreviewImage();
    }, this.data.refreshInterval);
  },

  // 停止刷新
  stopPreviewRefresh: function() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  },

  // 获取预览图片
  fetchPreviewImage: function() {
    const deviceId = this.data.device.deviceId;
    
    // 从云存储获取最新的预览图
    // 预览图保存在 preview/{deviceId}/frame.jpg
    const cloudPath = `preview/${deviceId}/frame.jpg`;
    
    // 添加时间戳避免缓存
    const timestamp = Date.now();
    
    // 尝试两个可能的存储桶ID
    const fileId1 = `cloud://cloudbase-0gt2twhpdc512c30.636c-cloudbase-0gt2twhpdc512c30-1301176645/${cloudPath}`;
    const fileId2 = `cloud://cloudbase-0gt2twhpdc512c30.636c-cloudbase-0gt2twhpdc512c30-1330571541/${cloudPath}`;
    
    wx.cloud.getTempFileURL({
      fileList: [fileId1, fileId2]
    }).then(res => {
      console.log('获取预览图结果:', res);
      // 查找有效的临时URL（可能来自两个存储桶中的任一个）
      let validUrl = null;
      if (res.fileList && res.fileList.length > 0) {
        for (let file of res.fileList) {
          if (file.tempFileURL && file.status === 0) {
            validUrl = file.tempFileURL;
            break;
          }
        }
      }
      
      if (validUrl) {
        const url = validUrl + '?t=' + timestamp;
        this.setData({
          previewUrl: url,
          previewLoading: false,
          previewError: false,
          lastUpdateTime: this.formatTime(new Date())
        });
      } else {
        // 如果刚开始预览，可能还没有图片，继续等待
        if (Date.now() - this.startTime < 10000) {
          console.log('等待预览图...');
        } else {
          this.setData({
            previewLoading: false,
            previewError: true,
            errorMessage: '获取预览图失败，请确认设备在线'
          });
        }
      }
    }).catch(err => {
      console.error('获取预览图失败', err);
      // 如果刚开始预览，继续等待
      if (Date.now() - this.startTime < 10000) {
        console.log('等待预览图...');
      } else {
        this.setData({
          previewLoading: false,
          previewError: true,
          errorMessage: '预览图加载失败'
        });
      }
    });
  },

  // 格式化时间
  formatTime: function(date) {
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    const seconds = date.getSeconds().toString().padStart(2, '0');
    return `${hours}:${minutes}:${seconds}`;
  },

  // 刷新
  onRefresh: function() {
    if (this.data.isStreaming) {
      this.fetchPreviewImage();
    } else {
      this.checkDeviceStatus();
    }
  },

  // 图片加载错误
  onImageError: function() {
    console.log('预览图加载错误');
  },

  // 返回
  goBack: function() {
    wx.navigateBack();
  }
});
