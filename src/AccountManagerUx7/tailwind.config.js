const plugin = require('tailwindcss/plugin')
module.exports = {
  content: ["index.html",'./client/**/*.{html,js}'],
  theme: {
      extend: {
        backgroundImage: {
          'dino': "url('/media/5715116.png')"
        },
        backgroundSize: {
          '48' : "48px"
        },
        gridTemplateColumns: {
          'auto-fill-150': 'repeat(auto-fill, minmax(150px, 1fr))',
          'auto-fit-150': 'repeat(auto-fit, minmax(150px, 1fr))',
        },
        gridTemplateRows: {
          'auto-fill-150': 'repeat(auto-fill, minmax(150px, 1fr))',
          'auto-fit-150': 'repeat(auto-fit, minmax(150px, 1fr))',
        }
      }
  },
  plugins: [],

}
