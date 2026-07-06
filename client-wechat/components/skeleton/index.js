Component({
  properties: {
    type: {
      type: String,
      value: "list"
    },
    hasTabbar: {
      type: Boolean,
      value: false
    }
  },
  data: {
    four: [1, 2, 3, 4],
    five: [1, 2, 3, 4, 5],
    six: [1, 2, 3, 4, 5, 6]
  }
});
