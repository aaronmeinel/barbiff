module.exports = {
  content: [
    './src/**/*',
    './resources/**/*',
  ],
  theme: {
    extend: {
      screens: {
        'desktop': '1024px',
      },
    },
  },
  plugins: [
    require('@tailwindcss/forms'),
  ],
}
