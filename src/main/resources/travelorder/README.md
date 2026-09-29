# Travel order (cestovný príkaz) resources

- `countries.geojson` - European country borders (ISO 3166-1 alpha-2 in
  `iso`), from Natural Earth `ne_10m_admin_0_countries` (public domain,
  https://www.naturalearthdata.com), coordinates rounded to 4 decimals.
  Used to find the country of every GPS position (hours abroad, border
  crossing time - zákon 283/2002 § 13, § 16).
- `NotoSans-*.ttf` - Noto Sans (SIL Open Font License 1.1, see `OFL.txt`),
  embedded in the travel order PDF for the Slovak diacritics.
- `travelorder.vm` - the PDF page template (Velocity, XHTML for
  openhtmltopdf: CSS 2.1 tables, no flex/grid).
