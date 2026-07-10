# AV Fishing Custom Changes

Perubahan yang ditambahkan:

1. Fishing custom bisa didapat tanpa event/competition.
   - `fishing.catch-enabled: true`
   - `fishing.catch-only-in-competition: false`

2. Vanilla fishing rod diblok dari sistem custom fishing.
   - `fishing.require-custom-rod: true`
   - `fishing.block-vanilla-rod-when-custom-required: true`

3. Harga ikan dibuat minimal sekitar $1,000 dan maksimal sekitar $20,000.
   - Junk: $1,000 - $1,500
   - Common: $1,500 - $3,000
   - Rare: $3,500 - $6,000
   - Epic: $7,000 - $12,000
   - Legendary: $10,000 - $14,000
   - Mythic: $14,000 - $17,000
   - Divine: $17,000 - $20,000

4. Tier baru:
   - Mythic
   - Divine

5. Custom rods:
   - Rookie Fisher Rod
   - Advanced Fisher Rod
   - Elite Fisher Rod
   - Mythic Fisher Rod
   - Divine Fisher Rod

6. Chance rarity per rod memakai `rarity-weights` di file rod.
   - Rod tinggi tetap tidak terlalu OP.
   - Divine Fisher Rod hanya punya Divine chance 0.2%.

7. Rod shop GUI ditambahkan ke `/emf` main GUI.
   - GUI baru: `rod-shop-menu` di `guis.yml`
   - Harga rod ada di `config.yml` bagian `rod-shop.prices`

8. Fishing minigame custom:
   - Saat ikan menggigit, player harus right click.
   - Setelah hook, player spam Shift untuk narik ikan.
   - Bobber bergerak mendekat ke player saat ditarik.
   - Ikan bisa melawan dan mengurangi progress.
   - Kalau player tidak narik dalam beberapa detik, ikan lepas.

Catatan build:
- Build belum bisa dites di container ini karena Gradle wrapper butuh download dari services.gradle.org, sedangkan network container tidak tersedia.
- File source sudah disiapkan untuk dipush ke GitHub dan dibuild lewat GitHub Actions atau Termux/server yang ada internet.
