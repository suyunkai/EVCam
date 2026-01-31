// pages/files/files.js
const app = getApp();

Page({
  data: {
    device: null,
    files: [],
    loading: true,
    currentTab: 'photo',  // video 或 photo，默认显示照片
    hasMore: true,
    page: 1,
    pageSize: 20
  },

  onLoad: function() {
    const device = app.globalData.boundDevice;
    if (!device) {
      wx.showToast({
        title: '未绑定设备',
        icon: 'none'
      });
      setTimeout(() => wx.navigateBack(), 1500);
      return;
    }

    this.setData({ device: device });
    this.loadFiles();
  },

  onShow: function() {
    // 每次显示时刷新
    if (this.data.device) {
      this.setData({ page: 1, hasMore: true });
      this.loadFiles();
    }
  },

  // 切换标签
  switchTab: function(e) {
    const tab = e.currentTarget.dataset.tab;
    if (tab === this.data.currentTab) return;

    this.setData({
      currentTab: tab,
      files: [],
      page: 1,
      hasMore: true
    });
    this.loadFiles();
  },

  // 加载文件列表
  loadFiles: function() {
    this.setData({ loading: true });

    wx.cloud.callFunction({
      name: 'getFileList',
      data: {
        deviceId: this.data.device.deviceId,
        fileType: this.data.currentTab,
        page: this.data.page,
        pageSize: this.data.pageSize
      }
    }).then(res => {
      this.setData({ loading: false });

      if (res.result && res.result.success) {
        const newFiles = res.result.files || [];
        
        // 处理文件数据，添加格式化的时间和大小
        const processedFiles = newFiles.map(file => ({
          ...file,
          createTimeStr: this.formatTime(file.createTime),
          fileSizeStr: this.formatFileSize(file.fileSize),
          // 是否已上传到云存储
          isUploaded: !!file.tempFileURL || (file.uploaded === true)
        }));
        
        this.setData({
          files: this.data.page === 1 ? processedFiles : [...this.data.files, ...processedFiles],
          hasMore: newFiles.length === this.data.pageSize
        });
      } else {
        if (this.data.page === 1) {
          this.setData({ files: [] });
        }
        // 可能是没有文件，不需要显示错误
        console.log('加载文件结果:', res.result?.message || '无文件');
      }
    }).catch(err => {
      this.setData({ loading: false });
      console.error('加载文件失败', err);
      if (this.data.page === 1) {
        this.setData({ files: [] });
      }
    });
  },

  // 加载更多
  loadMore: function() {
    if (!this.data.hasMore || this.data.loading) return;

    this.setData({ page: this.data.page + 1 });
    this.loadFiles();
  },

  // 预览文件
  previewFile: function(e) {
    const file = e.currentTarget.dataset.file;

    // 检查文件是否已上传到云存储
    if (!file.isUploaded && !file.tempFileURL) {
      wx.showModal({
        title: '提示',
        content: '此文件保存在车机设备上，尚未同步到云端。请连接设备后通过电脑导出查看。',
        showCancel: false,
        confirmText: '我知道了'
      });
      return;
    }

    if (this.data.currentTab === 'photo') {
      // 预览图片
      const urls = this.data.files
        .filter(f => f.tempFileURL)
        .map(f => f.tempFileURL);
      
      if (urls.length > 0) {
        wx.previewImage({
          current: file.tempFileURL,
          urls: urls
        });
      }
    } else {
      // 预览视频
      if (file.tempFileURL) {
        wx.previewMedia({
          sources: [{
            url: file.tempFileURL,
            type: 'video'
          }]
        });
      }
    }
  },

  // 删除文件
  deleteFile: function(e) {
    const file = e.currentTarget.dataset.file;

    wx.showModal({
      title: '删除文件',
      content: '确定要删除这个文件记录吗？',
      success: (res) => {
        if (res.confirm) {
          this.doDeleteFile(file);
        }
      }
    });
  },

  doDeleteFile: function(file) {
    wx.showLoading({ title: '删除中...' });

    wx.cloud.callFunction({
      name: 'deleteFile',
      data: {
        fileId: file.fileId,
        recordId: file._id
      }
    }).then(res => {
      wx.hideLoading();

      if (res.result && res.result.success) {
        // 从列表中移除
        const files = this.data.files.filter(f => f._id !== file._id);
        this.setData({ files: files });

        wx.showToast({
          title: '已删除',
          icon: 'success'
        });
      } else {
        wx.showToast({
          title: res.result?.message || '删除失败',
          icon: 'none'
        });
      }
    }).catch(err => {
      wx.hideLoading();
      console.error('删除失败', err);
      wx.showToast({
        title: '删除失败',
        icon: 'none'
      });
    });
  },

  // 下拉刷新
  onPullDownRefresh: function() {
    this.setData({ page: 1, hasMore: true });
    this.loadFiles();
    wx.stopPullDownRefresh();
  },

  // 上拉加载更多
  onReachBottom: function() {
    this.loadMore();
  },

  // 格式化时间
  formatTime: function(timestamp) {
    if (!timestamp) return '未知时间';
    const date = new Date(timestamp);
    const month = date.getMonth() + 1;
    const day = date.getDate();
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    return `${month}/${day} ${hours}:${minutes}`;
  },

  // 格式化文件大小
  formatFileSize: function(bytes) {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    return (bytes / 1024 / 1024 / 1024).toFixed(1) + ' GB';
  }
});
