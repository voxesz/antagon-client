const DEFAULT_IMAGE = '../assets/home-castle.png';
// Keep custom launcher art in the banner without rendering a full-screen scene.
export async function setWallpaper(custom) {
  const image = document.querySelector('#home-art');
  image.alt = custom ? 'Imagem personalizada da tela inicial' : 'Castelo gótico sob a montanha-caveira';
  image.onerror = () => {
    image.onerror = null;
    image.src = DEFAULT_IMAGE;
  };
  image.src = custom || DEFAULT_IMAGE;
}
