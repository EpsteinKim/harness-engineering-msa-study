-- 학습용 시드 데이터 (10,000명)
-- 사전 조건: ddl-auto=update로 user_account 테이블이 한 번 생성되어 있어야 함
-- 실행: psql "$USER_DB_URL" -f user-service/src/main/resources/db/seed.sql

-- 한국식 이름: 성(50) × 이름1음절(50) × 이름2음절(50) = 125,000 조합
WITH surnames AS (
  SELECT ARRAY[
    '김','이','박','최','정','강','조','윤','장','임',
    '한','오','서','신','권','황','안','송','류','전',
    '홍','고','문','양','손','배','백','허','유','남',
    '심','노','하','곽','성','차','주','우','구','민',
    '나','지','진','엄','채','원','천','방','공','현'
  ] AS arr
),
syl1 AS (
  SELECT ARRAY[
    '민','서','지','예','도','시','수','주','선','채',
    '준','현','유','하','은','우','아','연','윤','진',
    '재','승','태','동','용','성','석','재','종','광',
    '경','영','호','상','정','진','희','수','보','다',
    '소','나','루','새','미','윤','한','강','유','이'
  ] AS arr
),
syl2 AS (
  SELECT ARRAY[
    '준','연','호','윤','우','아','빈','진','희','민',
    '서','영','수','정','경','원','은','지','후','현',
    '율','한','솔','별','담','담','람','산','휘','결',
    '슬','울','달','벼','달','빛','담','별','울','승',
    '범','찬','진','은','솔','령','겸','준','민','우'
  ] AS arr
)
INSERT INTO user_account (email, name, password, created_at)
SELECT
  'user' || g || '@example.com',
  (SELECT arr[((g - 1) % 50) + 1] FROM surnames) ||
  (SELECT arr[(((g - 1) / 50) % 50) + 1] FROM syl1) ||
  (SELECT arr[(((g - 1) / 2500) % 50) + 1] FROM syl2),
  'pw' || g,
  now()
FROM generate_series(1, 10000) AS g
ON CONFLICT (email) DO NOTHING;

-- IDENTITY 시퀀스 보정 (혹시 모를 후속 INSERT 충돌 방지)
SELECT setval(
  pg_get_serial_sequence('user_account', 'id'),
  (SELECT COALESCE(MAX(id), 1) FROM user_account)
);
