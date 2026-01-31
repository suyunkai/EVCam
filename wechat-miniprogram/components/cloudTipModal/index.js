Component({

  /**
   * 页面的初始数据
   */
  data: {
    showTip: false,
  },
  properties: {
    showTipProps: Boolean,
    title:String,
    content:String
  },
  observers: {
    showTipProps: function(showTipProps) {
      this.setData({
        showTip: showTipProps
      });
    }
  },
  methods: {
    onClose(){
      this.setData({
        showTip: !this.data.showTip
      });
    },
  }
});
