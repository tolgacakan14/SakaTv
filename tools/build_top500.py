#!/usr/bin/env python3
"""
Şaka TV film arşivini (app/src/main/assets/films.json) IMDb'nin en iyi 500 filmiyle yeniden üretir.

Kullanım (Mac'te, proje klasöründe):
    python3 tools/build_top500.py --key TMDB_ANAHTARIN

Ne yapar:
  1. IMDb'nin ticari olmayan veri setlerini indirir (title.basics, title.ratings, title.crew, name.basics).
  2. Sadece uzun metrajlı filmleri alır, 25.000 oy altını eler,
     IMDb tarzı ağırlıklı puanla sıralar ve ilk 500'ü seçer.
  3. Her film için TMDB'den Türkçe ad, Türkçe özet, afiş, süre, tür, oyuncular ve YouTube fragmanını çeker.
  4. films.json'u yazar. Uygulamayı yeniden derleyince yeni arşiv gelir.

Not: IMDb veri setleri kişisel ve ticari olmayan kullanım içindir.
"""
import argparse, csv, gzip, io, json, os, sys, time, urllib.request, urllib.parse

IMDB = "https://datasets.imdbws.com/"
MIN_VOTES = 25000
TR_GENRE = {"Drama": "Dram", "Crime": "Suç", "Action": "Aksiyon", "Adventure": "Macera", "Comedy": "Komedi",
    "Romance": "Romantik", "Thriller": "Gerilim", "Horror": "Korku", "Sci-Fi": "Bilimkurgu", "Fantasy": "Fantastik",
    "Animation": "Animasyon", "Mystery": "Gizem", "War": "Savaş", "History": "Tarih", "Biography": "Biyografi",
    "Western": "Western", "Music": "Müzik", "Musical": "Müzikal", "Family": "Aile", "Sport": "Spor", "Film-Noir": "Kara film"}
TOP = 500
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "films.json")
CACHE = os.path.join(HERE, ".cache")


def fetch(url, cache_name=None):
    if cache_name:
        os.makedirs(CACHE, exist_ok=True)
        p = os.path.join(CACHE, cache_name)
        if os.path.exists(p) and time.time() - os.path.getmtime(p) < 7 * 86400:
            return open(p, "rb").read()
    req = urllib.request.Request(url, headers={"User-Agent": "SakaTV-archive-builder"})
    data = urllib.request.urlopen(req, timeout=120).read()
    if cache_name:
        open(os.path.join(CACHE, cache_name), "wb").write(data)
    return data


def tsv(name):
    print(f"  indiriliyor: {name}")
    raw = gzip.decompress(fetch(IMDB + name, name))
    return csv.DictReader(io.StringIO(raw.decode("utf-8")), delimiter="\t", quoting=csv.QUOTE_NONE)


def tmdb(key, path, **params):
    params["api_key"] = key
    url = "https://api.themoviedb.org/3" + path + "?" + urllib.parse.urlencode(params)
    for attempt in range(3):
        try:
            return json.loads(urllib.request.urlopen(url, timeout=20).read())
        except Exception:
            time.sleep(1 + attempt)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", default="", help="TMDB API anahtarı (v3). Boşsa sadece IMDb verisiyle yazar, afiş ve özetleri uygulama TMDB'den canlı tamamlar.")
    ap.add_argument("--top", type=int, default=TOP)
    a = ap.parse_args()

    print("1/3 IMDb puanları")
    ratings = {}
    for r in tsv("title.ratings.tsv.gz"):
        v = int(r["numVotes"])
        if v >= MIN_VOTES:
            ratings[r["tconst"]] = (float(r["averageRating"]), v)

    movies = {}
    for r in tsv("title.basics.tsv.gz"):
        t = r["tconst"]
        if t in ratings and r["titleType"] == "movie" and r["isAdult"] == "0":
            movies[t] = r

    # IMDb'nin bilinen ağırlıklı puan formülü: WR = v/(v+m)*R + m/(v+m)*C
    C = sum(ratings[t][0] for t in movies) / max(1, len(movies))
    m = MIN_VOTES
    def wr(t):
        R, v = ratings[t]
        return v / (v + m) * R + m / (v + m) * C
    top = sorted(movies, key=wr, reverse=True)[: a.top]
    print(f"   {len(movies)} aday, ilk {len(top)} seçildi")

    print("2/3 yönetmenler")
    need = set(top)
    dirs = {}
    for r in tsv("title.crew.tsv.gz"):
        if r["tconst"] in need and r["directors"] != "\\N":
            dirs[r["tconst"]] = r["directors"].split(",")[:2]
    people = {p for v in dirs.values() for p in v}
    names = {}
    for r in tsv("name.basics.tsv.gz"):
        if r["nconst"] in people:
            names[r["nconst"]] = r["primaryName"]
    existing = {}
    if os.path.exists(OUT):
        for e in json.load(open(OUT, encoding="utf-8")):
            existing[e["imdb"]] = e

    print("3/3 film kayıtları" + (" ve TMDB ayrıntıları (biraz sürer)" if a.key else ""))
    out = []
    for i, t in enumerate(top, 1):
        b = movies[t]
        if not a.key:
            old = existing.get(t, {})
            g = b["genres"].replace("\\N", "").split(",")[:2]
            out.append({
                "imdb": t, "tmdb": old.get("tmdb", 0),
                "title": old.get("title") or b["primaryTitle"],
                "original": b["originalTitle"], "year": b["startYear"],
                "rating": ratings[t][0],
                "genres": old.get("genres") or ", ".join(TR_GENRE.get(x, x) for x in g if x),
                "runtime": int(b["runtimeMinutes"]) if b["runtimeMinutes"].isdigit() else 0,
                "overview": old.get("overview", ""), "poster": old.get("poster", ""),
                "director": ", ".join(names.get(n, "") for n in dirs.get(t, []) if names.get(n)),
                "cast": old.get("cast", ""), "trailer": old.get("trailer", ""),
            })
            continue
        f = tmdb(a.key, f"/find/{t}", external_source="imdb_id")
        res = (f or {}).get("movie_results") or []
        if not res:
            continue
        mid = res[0]["id"]
        d = tmdb(a.key, f"/movie/{mid}", language="tr-TR", append_to_response="credits,videos") or {}
        vids = [v for v in (d.get("videos") or {}).get("results", []) if v.get("site") == "YouTube"]
        if not vids:
            en = tmdb(a.key, f"/movie/{mid}/videos", language="en-US") or {}
            vids = [v for v in en.get("results", []) if v.get("site") == "YouTube"]
        trailer = next((v["key"] for v in vids if v.get("type") == "Trailer"), vids[0]["key"] if vids else "")
        crew = (d.get("credits") or {}).get("crew", [])
        cast = (d.get("credits") or {}).get("cast", [])
        overview = d.get("overview") or ""
        if not overview:
            en = tmdb(a.key, f"/movie/{mid}", language="en-US") or {}
            overview = en.get("overview") or ""
        out.append({
            "imdb": t,
            "tmdb": mid,
            "title": d.get("title") or b["primaryTitle"],
            "original": d.get("original_title") or b["originalTitle"],
            "year": b["startYear"],
            "rating": ratings[t][0],
            "genres": ", ".join(g["name"] for g in (d.get("genres") or [])[:2]),
            "runtime": d.get("runtime") or (int(b["runtimeMinutes"]) if b["runtimeMinutes"].isdigit() else 0),
            "overview": overview,
            "poster": d.get("poster_path") or "",
            "director": ", ".join(c["name"] for c in crew if c.get("job") == "Director") or ", ".join(names.get(n, "") for n in dirs.get(t, []) if names.get(n)),
            "cast": ", ".join(c["name"] for c in cast[:3]),
            "trailer": trailer,
        })
        if i % 25 == 0:
            print(f"   {i}/{len(top)}")
        time.sleep(0.05)

    print("yazılıyor")
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    print(f"Tamam: {len(out)} film -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    sys.exit(main())
