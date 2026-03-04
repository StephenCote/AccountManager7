export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts}"
  ],
  theme: {
    extend: {
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
  darkMode: 'class',
  plugins: [],
};
